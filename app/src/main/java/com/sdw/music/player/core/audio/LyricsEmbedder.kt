package com.sdw.music.player.core.audio

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

/**
 * 内嵌歌词写入器：把编辑后的歌词写进音频文件 tag。
 * - FLAC：写入/替换 VORBIS_COMMENT metadata block 的 LYRICS 字段（小端）
 * - MP3 ：写入/替换 ID3v2 的 USLT 帧（UTF-8，language "XXX"）
 * - 其他格式：不支持，返回 false
 *
 * 采用「读 metadata 到内存 → 重建 → 写 .tmp → 原子 rename」的流式策略，
 * 音频数据体不加载进内存（与 AudioCoverEmbedder 一致）。
 * 返回 false 时不影响外部 .lrc 文件的保存（歌词已持久化，内嵌是附加动作）。
 */
object LyricsEmbedder {

    /**
     * 内嵌歌词。返回是否成功。仅支持 FLAC / MP3。
     * @param filePath 音频文件真实路径（非 content URI）
     */
    fun embedLyrics(filePath: String, lyrics: String): Boolean {
        val tag = "LyricsEmbedder"
        if (lyrics.isBlank()) { android.util.Log.w(tag, "lyrics blank"); return false }
        val file = File(filePath)
        if (!file.exists()) { android.util.Log.w(tag, "file not exist: $filePath"); return false }
        if (!file.canRead()) { android.util.Log.w(tag, "file not readable: $filePath"); return false }
        // 权限判断：Android 11+ 重写共享存储音频文件需「所有文件访问」授权；
        // canWrite() 在 scoped storage 下会误判，不可靠。
        if (!canWriteAudioFile(file)) {
            android.util.Log.w(tag, "no write access (isExternalStorageManager=false): $filePath")
            return false
        }

        return try {
            val header = ByteArray(16)
            FileInputStream(file).use { it.read(header) }
            val fmt = detectFormat(header)
            android.util.Log.d(tag, "detectFormat=$fmt")
            when (fmt) {
                "flac" -> embedFlacLyrics(file, lyrics)
                "mp3" -> embedMp3Lyrics(file, lyrics)
                else -> false
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "embedLyrics failed: ${e.message}")
            false
        }
    }

    /**
     * 真实可写判断：
     * - Android 11+：共享存储重写需 All Files Access（Environment.isExternalStorageManager）
     * - Android 10-：requestLegacyExternalStorage 已声明，canWrite 基本可靠
     */
    private fun canWriteAudioFile(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return file.canWrite()
    }

    private fun detectFormat(data: ByteArray): String = when {
        data.size >= 4 && data[0] == 0x66.toByte() && data[1] == 0x4C.toByte()
            && data[2] == 0x61.toByte() && data[3] == 0x43.toByte() -> "flac"
        data.size >= 3 && data[0] == 0x49.toByte() && data[1] == 0x44.toByte()
            && data[2] == 0x33.toByte() -> "mp3"
        data.size >= 2 && (data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0) -> "mp3"
        else -> "unknown"
    }

    // ============ FLAC ============

    private fun embedFlacLyrics(file: File, lyrics: String): Boolean {
        val tag = "LyricsEmbedder"
        val raf = RandomAccessFile(file, "r")
        try {
            // "fLaC" marker
            val marker = ByteArray(4)
            raf.readFully(marker)
            if (marker[0] != 0x66.toByte() || marker[1] != 0x4C.toByte()
                || marker[2] != 0x61.toByte() || marker[3] != 0x43.toByte()
            ) { android.util.Log.w(tag, "bad fLaC marker"); return false }

            // 收集所有 metadata block（header 4 字节 + body）
            val blocks = mutableListOf<ByteArray>()
            var lastBlock = false
            var foundVc = false
            val maxMetaBytes = 8 * 1024 * 1024 // 8MB，容纳大封面 PICTURE block

            while (!lastBlock && raf.filePointer < raf.length()) {
                if (raf.filePointer >= maxMetaBytes) { android.util.Log.w(tag, "meta > 8MB, bail"); return false }
                val h0 = raf.readUnsignedByte()
                if (h0 == 0xFF) break // 音频帧，异常情况
                lastBlock = (h0 and 0x80) != 0
                val blockType = h0 and 0x7F
                val b0 = raf.readUnsignedByte()
                val b1 = raf.readUnsignedByte()
                val b2 = raf.readUnsignedByte()
                val blockSize = (b0 shl 16) or (b1 shl 8) or b2
                if (blockSize > maxMetaBytes || blockSize > raf.length() - raf.filePointer) {
                    android.util.Log.w(tag, "blockSize=$blockSize too big (type=$blockType), bail")
                    return false
                }

                val blockData = ByteArray(4 + blockSize)
                blockData[0] = h0.toByte()
                blockData[1] = b0.toByte()
                blockData[2] = b1.toByte()
                blockData[3] = b2.toByte()
                raf.readFully(blockData, 4, blockSize)

                if (blockType == 4) {
                    // VORBIS_COMMENT：重建（插入/替换 LYRICS 字段）
                    blocks.add(rebuildVorbisComment(blockData, lyrics) ?: blockData)
                    foundVc = true
                } else {
                    blocks.add(blockData)
                }
            }

            if (blocks.isEmpty()) { android.util.Log.w(tag, "no metadata blocks"); return false }
            android.util.Log.d(tag, "flac: ${blocks.size} blocks, foundVc=$foundVc")

            val audioStart = raf.filePointer

            // 若原文件没有 VORBIS_COMMENT，新建一个插到 STREAMINFO（首个 block）之后
            if (!foundVc) {
                val vc = buildVorbisComment(lyrics, isLast = false)
                // STREAMINFO 是 blocks[0]，其后插入
                blocks.add(1, vc)
            }

            // 统一重写 last-flag：全部清 0，最后一个设 1
            for ((i, blk) in blocks.withIndex()) {
                blk[0] = if (i == blocks.size - 1) (blk[0].toInt() or 0x80).toByte()
                        else (blk[0].toInt() and 0x7F).toByte()
            }

            // 写 .tmp → 原子替换
            val tempFile = File(file.absolutePath + ".lyrtmp")
            try {
                FileOutputStream(tempFile).use { out ->
                    out.write(marker)
                    for (blk in blocks) out.write(blk)
                    raf.seek(audioStart)
                    val buf = ByteArray(65536)
                    var n: Int
                    while (raf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
                raf.close()
                if (!tempFile.renameTo(file)) {
                    android.util.Log.w(tag, "renameTo failed: ${tempFile.absolutePath}")
                    tempFile.delete()
                    return false
                }
                android.util.Log.d(tag, "flac embed OK: ${file.name}")
                return true
            } catch (e: Exception) {
                android.util.Log.w(tag, "write tmp failed: ${e.message}")
                tempFile.delete()
                throw e
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /**
     * 重建 VORBIS_COMMENT block：解析现有 comment 条目，过滤 LYRICS/UNSYNCEDLYRICS，
     * 追加新的 LYRICS=lyrics。解析失败返回 null（保留原 block）。
     */
    private fun rebuildVorbisComment(block: ByteArray, lyrics: String): ByteArray? {
        return try {
            val body = block.copyOfRange(4, block.size)
            var pos = 0

            fun leInt(b: ByteArray, off: Int): Int =
                (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                    ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

            if (pos + 4 > body.size) return null
            val vendorLen = leInt(body, pos); pos += 4
            if (vendorLen < 0 || pos + vendorLen > body.size) return null
            val vendor = body.copyOfRange(pos, pos + vendorLen); pos += vendorLen

            if (pos + 4 > body.size) return null
            val count = leInt(body, pos); pos += 4
            if (count < 0) return null

            // 解析每条 comment，过滤 LYRICS/UNSYNCEDLYRICS
            val kept = ArrayList<ByteArray>()
            for (i in 0 until count) {
                if (pos + 4 > body.size) return null
                val len = leInt(body, pos); pos += 4
                if (len < 0 || pos + len > body.size) return null
                val entry = body.copyOfRange(pos, pos + len); pos += len
                val eq = entry.indexOf(0x3D) // '='
                if (eq > 0) {
                    val field = String(entry, 0, eq, StandardCharsets.US_ASCII).uppercase()
                    if (field == "LYRICS" || field == "UNSYNCEDLYRICS") continue // 丢弃旧歌词字段
                }
                kept.add(entry)
            }

            // 追加新 LYRICS 字段
            kept.add("LYRICS=$lyrics".toByteArray(StandardCharsets.UTF_8))

            // 重建 body
            val bos = ByteArrayOutputStream()
            fun writeLeInt(v: Int) {
                bos.write(v and 0xFF)
                bos.write((v shr 8) and 0xFF)
                bos.write((v shr 16) and 0xFF)
                bos.write((v shr 24) and 0xFF)
            }
            writeLeInt(vendor.size); bos.write(vendor)
            writeLeInt(kept.size)
            for (e in kept) { writeLeInt(e.size); bos.write(e) }
            val newBody = bos.toByteArray()

            // 新 header：type=4，last-flag 沿用原 block，size = newBody.size
            val out = ByteArrayOutputStream()
            out.write(block[0].toInt()) // 保留原 last-flag + type=4
            out.write((newBody.size shr 16) and 0xFF)
            out.write((newBody.size shr 8) and 0xFF)
            out.write(newBody.size and 0xFF)
            out.write(newBody)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** 新建一个 VORBIS_COMMENT block（仅含 LYRICS 字段） */
    private fun buildVorbisComment(lyrics: String, isLast: Boolean): ByteArray {
        val vendor = ByteArray(0)
        val entry = "LYRICS=$lyrics".toByteArray(StandardCharsets.UTF_8)

        val bos = ByteArrayOutputStream()
        fun writeLeInt(v: Int) {
            bos.write(v and 0xFF); bos.write((v shr 8) and 0xFF)
            bos.write((v shr 16) and 0xFF); bos.write((v shr 24) and 0xFF)
        }
        writeLeInt(0) // vendor length = 0
        writeLeInt(1) // 1 comment
        writeLeInt(entry.size); bos.write(entry)
        val body = bos.toByteArray()

        val header = ByteArrayOutputStream()
        header.write(if (isLast) 0x84 else 0x04) // type=4, last-flag
        header.write((body.size shr 16) and 0xFF)
        header.write((body.size shr 8) and 0xFF)
        header.write(body.size and 0xFF)
        header.write(body)
        return header.toByteArray()
    }

    // ============ MP3 ============

    private fun embedMp3Lyrics(file: File, lyrics: String): Boolean {
        val raf = RandomAccessFile(file, "r")
        try {
            val first3 = ByteArray(3)
            if (raf.length() >= 3) raf.read(first3)
            val hasId3v2 = first3[0] == 0x49.toByte() && first3[1] == 0x44.toByte() && first3[2] == 0x33.toByte()

            var existingId3: ByteArray? = null
            var id3Size = 0
            var audioStart = 0L

            if (hasId3v2 && raf.length() >= 10) {
                val sizeBytes = ByteArray(4)
                raf.seek(6); raf.read(sizeBytes)
                id3Size = synchSafeInt(sizeBytes[0], sizeBytes[1], sizeBytes[2], sizeBytes[3]) + 10
                audioStart = id3Size.toLong()
                if (id3Size <= 262144 && id3Size <= raf.length()) {
                    raf.seek(0)
                    existingId3 = ByteArray(id3Size)
                    raf.readFully(existingId3)
                } else {
                    audioStart = 0
                }
            }

            val usltBody = buildUsltFrame(lyrics)
            var newTagSize: Int
            var tagHeader: ByteArray

            if (existingId3 != null && existingId3.size > 10) {
                val stripped = stripId3Frame(existingId3, "USLT")
                newTagSize = (stripped.size - 10) + usltBody.size
                tagHeader = ByteArray(10)
                tagHeader[0] = 'I'.code.toByte(); tagHeader[1] = 'D'.code.toByte(); tagHeader[2] = '3'.code.toByte()
                tagHeader[3] = 3; tagHeader[4] = 0; tagHeader[5] = 0
                writeSynchSafeInt(tagHeader, 6, newTagSize)

                val tempFile = File(file.absolutePath + ".lyrtmp")
                try {
                    FileOutputStream(tempFile).use { out ->
                        out.write(tagHeader)
                        if (stripped.size > 10) out.write(stripped, 10, stripped.size - 10)
                        out.write(usltBody)
                        raf.seek(audioStart)
                        val buf = ByteArray(65536)
                        var n: Int
                        while (raf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                    raf.close()
                    if (!tempFile.renameTo(file)) { tempFile.delete(); return false }
                    return true
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            } else {
                newTagSize = usltBody.size
                tagHeader = ByteArray(10)
                tagHeader[0] = 'I'.code.toByte(); tagHeader[1] = 'D'.code.toByte(); tagHeader[2] = '3'.code.toByte()
                tagHeader[3] = 3; tagHeader[4] = 0; tagHeader[5] = 0
                writeSynchSafeInt(tagHeader, 6, newTagSize)

                val tempFile = File(file.absolutePath + ".lyrtmp")
                try {
                    FileOutputStream(tempFile).use { out ->
                        out.write(tagHeader)
                        out.write(usltBody)
                        raf.seek(audioStart)
                        val buf = ByteArray(65536)
                        var n: Int
                        while (raf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                    raf.close()
                    if (!tempFile.renameTo(file)) { tempFile.delete(); return false }
                    return true
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /** 构建 ID3v2.3 USLT 帧（Unsynced Lyrics，UTF-8） */
    private fun buildUsltFrame(lyrics: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x03) // text encoding = UTF-8
        body.write("XXX".toByteArray(StandardCharsets.US_ASCII)) // language
        body.write(0x00) // content descriptor = 空，null 终止
        body.write(lyrics.toByteArray(StandardCharsets.UTF_8))
        val bodyBytes = body.toByteArray()

        val frame = ByteArrayOutputStream()
        frame.write("USLT".toByteArray(StandardCharsets.US_ASCII))
        frame.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bodyBytes.size).array())
        frame.write(ByteArray(2)) // flags
        frame.write(bodyBytes)
        return frame.toByteArray()
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
