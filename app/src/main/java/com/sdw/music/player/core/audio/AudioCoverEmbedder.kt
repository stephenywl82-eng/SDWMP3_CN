package com.sdw.music.player.core.audio

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object AudioCoverEmbedder {

    sealed class EmbedResult {
        data object Success : EmbedResult()
        data class Failure(val reason: String) : EmbedResult()
    }

    fun embedCover(
        context: Context,
        filePath: String,
        coverBytes: ByteArray
    ): EmbedResult {
        val songFile = File(filePath)
        if (!songFile.exists() || !songFile.canRead())
            return EmbedResult.Failure("Cannot read: $filePath")
        if (!canWriteAudioFile(songFile))
            return EmbedResult.Failure("Cannot write: $filePath. Grant 'All files access' in Settings.")

        return try {
            // Detect format from header only (first 16 bytes)
            val header = ByteArray(16)
            FileInputStream(songFile).use { it.read(header) }
            val format = detectFormat(header)

            when (format) {
                "flac" -> embedFlacStream(songFile, coverBytes)
                "mp3" -> embedMp3Stream(songFile, coverBytes)
                "m4a" -> return EmbedResult.Failure("M4A not supported yet")
                else -> return EmbedResult.Failure("Unsupported format: $format")
            }
        } catch (e: Exception) {
            EmbedResult.Failure(e.message ?: "Unknown error")
        }
    }

    private fun detectFormat(data: ByteArray): String {
        return when {
            data.size >= 4 && data[0] == 0x66.toByte() && data[1] == 0x4C.toByte()
                && data[2] == 0x61.toByte() && data[3] == 0x43.toByte() -> "flac"
            data.size >= 3 && data[0] == 0x49.toByte() && data[1] == 0x44.toByte()
                && data[2] == 0x33.toByte() -> "mp3"
            data.size >= 2 && (data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0) -> "mp3"
            else -> "m4a"
        }
    }

    /**
     * 真实可写判断（同 LyricsEmbedder）：
     * Android 11+ 重写共享存储音频文件需 All Files Access，canWrite() 会误判。
     */
    private fun canWriteAudioFile(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return file.canWrite()
    }

    // ---- FLAC streaming ----
    // Only reads metadata blocks into memory, audio data is copied via stream
    private fun embedFlacStream(file: File, coverBytes: ByteArray): EmbedResult {
        val raf = RandomAccessFile(file, "r")
        return try {
            // Read "fLaC" marker
            val marker = ByteArray(4)
            raf.read(marker)
            if (marker[0] != 0x66.toByte() || marker[1] != 0x4C.toByte()
                || marker[2] != 0x61.toByte() || marker[3] != 0x43.toByte()) {
                return EmbedResult.Failure("Not a valid FLAC file")
            }

            // Collect metadata blocks (they're small �?usually < 8KB total)
            val metaBlocks = mutableListOf<ByteArray>()
            var lastBlock = false
            var lastBlockType = 0
            val maxMetaBytes = 65536 // 64KB safety limit

            while (!lastBlock && raf.filePointer < raf.length()) {
                if (raf.filePointer >= maxMetaBytes) {
                    return EmbedResult.Failure("FLAC metadata too large")
                }
                val header = raf.readUnsignedByte()
                if (header == 0xFF) break // audio frames
                lastBlock = (header and 0x80) != 0
                val blockType = header and 0x7F
                val b0 = raf.readUnsignedByte()
                val b1 = raf.readUnsignedByte()
                val b2 = raf.readUnsignedByte()
                val blockSize = (b0 shl 16) or (b1 shl 8) or b2
                if (blockSize > maxMetaBytes || blockSize > raf.length() - raf.filePointer) {
                    return EmbedResult.Failure("Invalid FLAC block size")
                }
                // Check if PICTURE block already exists
                if (blockType == 6) return EmbedResult.Success // already has cover

                val blockData = ByteArray(4 + blockSize)
                blockData[0] = header.toByte()
                blockData[1] = b0.toByte()
                blockData[2] = b1.toByte()
                blockData[3] = b2.toByte()
                raf.readFully(blockData, 4, blockSize)
                metaBlocks.add(blockData)
                lastBlockType = blockType
            }

            if (metaBlocks.isEmpty()) return EmbedResult.Failure("No FLAC metadata blocks found")

            val audioStart = raf.filePointer

            // Build PICTURE block
            val picBlock = buildFlacPictureBlock(coverBytes)

            // Update last meta block header: clear is_last flag
            val lastBlockData = metaBlocks.last()
            lastBlockData[0] = (lastBlockData[0].toInt() and 0x7F).toByte() // clear bit 7

            // Write to temp file
            val tempFile = File(file.absolutePath + ".tmp")
            try {
                FileOutputStream(tempFile).use { out ->
                    out.write(marker)
                    for (block in metaBlocks) out.write(block)
                    out.write(picBlock)

                    // Stream audio data in 64KB chunks
                    val buf = ByteArray(65536)
                    raf.seek(audioStart)
                    var n: Int
                    while (raf.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
                // Atomic replace
                raf.close()
                if (!tempFile.renameTo(file)) {
                    tempFile.delete()
                    return EmbedResult.Failure("Failed to replace file")
                }
                EmbedResult.Success
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    private fun buildFlacPictureBlock(imageBytes: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(StandardCharsets.US_ASCII)
        val desc = "".toByteArray(StandardCharsets.US_ASCII)
        var width = 500; var height = 500; var cd = 24
        if (imageBytes.size > 100 && imageBytes[0] == 0xFF.toByte() && imageBytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i < imageBytes.size - 9) {
                if (imageBytes[i] == 0xFF.toByte() && imageBytes[i + 1] == 0xC0.toByte()) {
                    height = ((imageBytes[i + 5].toInt() and 0xFF) shl 8) or (imageBytes[i + 6].toInt() and 0xFF)
                    width = ((imageBytes[i + 7].toInt() and 0xFF) shl 8) or (imageBytes[i + 8].toInt() and 0xFF)
                    cd = imageBytes[i + 4].toInt() and 0xFF
                    break
                }
                i += ((imageBytes[i + 2].toInt() and 0xFF) shl 8 or (imageBytes[i + 3].toInt() and 0xFF)) + 2
            }
        }
        val bos = ByteArrayOutputStream()
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(3).array()) // type: front cover
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(mime.size).array()); bos.write(mime)
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(desc.size).array()); bos.write(desc)
        bos.write(ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height).putInt(cd).putInt(0).array())
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(imageBytes.size).array()); bos.write(imageBytes)
        val body = bos.toByteArray()
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .put((0x80 or 6).toByte()).put((body.size shr 16).toByte())
            .put(((body.size shr 8) and 0xFF).toByte()).put((body.size and 0xFF).toByte())
        header.rewind()
        return header.array() + body
    }

    // ---- MP3 streaming ----
    // ID3v2 tag is at the beginning. Read tag, audio is copied via stream.
    private fun embedMp3Stream(file: File, coverBytes: ByteArray): EmbedResult {
        val raf = RandomAccessFile(file, "r")
        return try {
            val first3 = ByteArray(3)
            if (raf.length() >= 3) {
                raf.read(first3)
            }
            val hasId3v2 = first3[0] == 0x49.toByte() && first3[1] == 0x44.toByte() && first3[2] == 0x33.toByte()

            var existingId3: ByteArray? = null
            var id3Size = 0
            var audioStart = 0L

            if (hasId3v2 && raf.length() >= 10) {
                val sizeBytes = ByteArray(4)
                raf.seek(6); raf.read(sizeBytes)
                id3Size = synchSafeInt(sizeBytes[0], sizeBytes[1], sizeBytes[2], sizeBytes[3]) + 10
                audioStart = id3Size.toLong()
                if (id3Size <= 262144 && id3Size <= raf.length()) { // max 256KB tag
                    raf.seek(0)
                    existingId3 = ByteArray(id3Size)
                    raf.readFully(existingId3)
                } else {
                    audioStart = 0 // corrupt tag, skip
                }
            }

            val apicBody = buildId3ApicFrame(coverBytes)
            var newTagSize: Int
            var tagHeader: ByteArray

            if (existingId3 != null && existingId3.size > 10) {
                val stripped = stripId3Frame(existingId3, "APIC")
                newTagSize = (stripped.size - 10) + apicBody.size
                tagHeader = ByteArray(10)
                tagHeader[0] = 'I'.code.toByte(); tagHeader[1] = 'D'.code.toByte(); tagHeader[2] = '3'.code.toByte()
                tagHeader[3] = 3; tagHeader[4] = 0; tagHeader[5] = 0
                writeSynchSafeInt(tagHeader, 6, newTagSize)

                val tempFile = File(file.absolutePath + ".tmp")
                try {
                    FileOutputStream(tempFile).use { out ->
                        out.write(tagHeader)
                        if (stripped.size > 10) out.write(stripped, 10, stripped.size - 10)
                        out.write(apicBody)
                        // Stream audio
                        raf.seek(audioStart)
                        val buf = ByteArray(65536)
                        var n: Int
                        while (raf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                    raf.close()
                    if (!tempFile.renameTo(file)) { tempFile.delete(); return EmbedResult.Failure("Failed to replace file") }
                    EmbedResult.Success
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            } else {
                newTagSize = apicBody.size
                tagHeader = ByteArray(10)
                tagHeader[0] = 'I'.code.toByte(); tagHeader[1] = 'D'.code.toByte(); tagHeader[2] = '3'.code.toByte()
                tagHeader[3] = 3; tagHeader[4] = 0; tagHeader[5] = 0
                writeSynchSafeInt(tagHeader, 6, newTagSize)

                val tempFile = File(file.absolutePath + ".tmp")
                try {
                    FileOutputStream(tempFile).use { out ->
                        out.write(tagHeader)
                        out.write(apicBody)
                        raf.seek(audioStart)
                        val buf = ByteArray(65536)
                        var n: Int
                        while (raf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                    raf.close()
                    if (!tempFile.renameTo(file)) { tempFile.delete(); return EmbedResult.Failure("Failed to replace file") }
                    EmbedResult.Success
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    private fun buildId3ApicFrame(imageBytes: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(StandardCharsets.US_ASCII)
        val desc = "".toByteArray(StandardCharsets.US_ASCII)
        val frameBodySize = 1 + mime.size + 1 + desc.size + imageBytes.size
        val bos = ByteArrayOutputStream()
        bos.write("APIC".toByteArray(StandardCharsets.US_ASCII))
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(frameBodySize).array())
        bos.write(ByteArray(2)) // flags
        bos.write(0) // text encoding
        bos.write(mime); bos.write(0)
        bos.write(3) // front cover
        bos.write(desc); bos.write(0)
        bos.write(imageBytes)
        return bos.toByteArray()
    }

    private fun stripId3Frame(tag: ByteArray, frameId: String): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(tag, 0, 10)
        var pos = 10
        while (pos + 10 <= tag.size) {
            if (tag[pos] == 0.toByte()) break
            val size = ((tag[pos + 4].toInt() and 0xFF) shl 24) or
                ((tag[pos + 5].toInt() and 0xFF) shl 16) or
                ((tag[pos + 6].toInt() and 0xFF) shl 8) or (tag[pos + 7].toInt() and 0xFF)
            val id = String(tag, pos, 4, StandardCharsets.US_ASCII)
            if (id != frameId) bos.write(tag, pos, 10 + size)
            pos += 10 + size
        }
        return bos.toByteArray()
    }

    private fun synchSafeInt(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int =
        ((b0.toInt() and 0xFF) shl 21) or ((b1.toInt() and 0xFF) shl 14) or
            ((b2.toInt() and 0xFF) shl 7) or (b3.toInt() and 0xFF)

    private fun writeSynchSafeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 21) and 0x7F).toByte()
        buf[offset + 1] = ((value shr 14) and 0x7F).toByte()
        buf[offset + 2] = ((value shr 7) and 0x7F).toByte()
        buf[offset + 3] = (value and 0x7F).toByte()
    }
}
