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
                "wav" -> embedWavLyrics(file, lyrics)
                else -> false
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "embedLyrics failed: ${e.message}")
            false
        }
    }

    /**
     * 【V8.117】 移除内嵌歌词。返回是否真的改动成功。仅支持 FLAC / MP3。
     * 与 embedLyrics 相反：把 LYRICS / UNSYNCEDLYRICS(VORBIS_COMMENT) 或 USLT(ID3v2) 字段删掉，
     * 其余标签与音频体原样保留。用于「清除内嵌歌词」——旧实现只写不删，而且写端拒空，
     * 所以错误的内嵌歌词永远擦不掉（读取端内嵌优先级最高）。
     */
    fun removeLyrics(filePath: String): Boolean {
        val tag = "LyricsEmbedder"
        val file = File(filePath)
        if (!file.exists()) { android.util.Log.w(tag, "file not exist: $filePath"); return false }
        if (!file.canRead()) { android.util.Log.w(tag, "file not readable: $filePath"); return false }
        if (!canWriteAudioFile(file)) {
            android.util.Log.w(tag, "no write access (isExternalStorageManager=false): $filePath")
            return false
        }
        return try {
            val header = ByteArray(16)
            FileInputStream(file).use { it.read(header) }
            val fmt = detectFormat(header)
            android.util.Log.d(tag, "removeLyrics detectFormat=$fmt")
            when (fmt) {
                "flac" -> removeFlacLyrics(file)
                "mp3" -> removeMp3Lyrics(file)
                "wav" -> removeWavLyrics(file)
                else -> false
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "removeLyrics failed: ${e.message}")
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

    // [V8.134] 原子替换。Android 的 FUSE 模拟存储上，renameTo() 覆盖**已存在的**目标
    // 可能返回 false（源文件本身没问题），于是每次写入都静默失败、还残留 .lyrtmp。
    // 先试 rename（同目录、真原子），失败再退化 copy+delete —— FUSE 上这条才是能成的。
    private fun replaceFile(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        return try {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output, 65536) }
            }
            src.delete()
            true
        } catch (e: Exception) {
            android.util.Log.w("LyricsEmbedder", "replaceFile failed: ${e.message}")
            false
        }
    }

    private fun detectFormat(data: ByteArray): String = when {
        data.size >= 4 && data[0] == 0x66.toByte() && data[1] == 0x4C.toByte()
            && data[2] == 0x61.toByte() && data[3] == 0x43.toByte() -> "flac"
        data.size >= 3 && data[0] == 0x49.toByte() && data[1] == 0x44.toByte()
            && data[2] == 0x33.toByte() -> "mp3"
        data.size >= 2 && (data[0] == 0xFF.toByte() && (data[1].toInt() and 0xE0) == 0xE0) -> "mp3"
        // [V8.129] WAV(RIFX)：内嵌标签在 RIFF 的 "id3 " chunk（Adobe/Wavelab 约定），
        // V8.87/V8.91 只做了「读」，写端一直没有 —— 用户主力曲库是 WAV，所以编辑永远写不回。
        data.size >= 12 &&
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
            data[8] == 0x57.toByte() && data[9] == 0x41.toByte() &&
            data[10] == 0x56.toByte() && data[11] == 0x45.toByte() -> "wav"
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
                if (!replaceFile(tempFile, file)) {
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

    private fun removeFlacLyrics(file: File): Boolean {
        val tag = "LyricsEmbedder"
        val raf = RandomAccessFile(file, "r")
        try {
            val marker = ByteArray(4)
            raf.readFully(marker)
            if (marker[0] != 0x66.toByte() || marker[1] != 0x4C.toByte()
                || marker[2] != 0x61.toByte() || marker[3] != 0x43.toByte()
            ) { android.util.Log.w(tag, "bad fLaC marker"); return false }

            val blocks = mutableListOf<ByteArray>()
            var lastBlock = false
            var foundVc = false
            val maxMetaBytes = 8 * 1024 * 1024

            while (!lastBlock && raf.filePointer < raf.length()) {
                if (raf.filePointer >= maxMetaBytes) { android.util.Log.w(tag, "meta > 8MB, bail"); return false }
                val h0 = raf.readUnsignedByte()
                if (h0 == 0xFF) break
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
                    blocks.add(stripVorbisCommentLyrics(blockData) ?: blockData)
                    foundVc = true
                } else {
                    blocks.add(blockData)
                }
            }

            if (!foundVc) { android.util.Log.d(tag, "flac: no VORBIS_COMMENT, nothing to remove"); return false }
            val audioStart = raf.filePointer

            for ((i, blk) in blocks.withIndex()) {
                blk[0] = if (i == blocks.size - 1) (blk[0].toInt() or 0x80).toByte()
                        else (blk[0].toInt() and 0x7F).toByte()
            }

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
                if (!replaceFile(tempFile, file)) {
                    android.util.Log.w(tag, "renameTo failed: ${tempFile.absolutePath}")
                    tempFile.delete()
                    return false
                }
                android.util.Log.d(tag, "flac removeLyrics OK: ${file.name}")
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
     * 解析并重建 VORBIS_COMMENT，删除 LYRICS / UNSYNCEDLYRICS 字段；本来就没有则返回 null（不改动）。
     * 解析失败也返回 null（保留原 block）。
     */
    private fun stripVorbisCommentLyrics(block: ByteArray): ByteArray? {
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

            val kept = ArrayList<ByteArray>()
            var removed = false
            for (i in 0 until count) {
                if (pos + 4 > body.size) return null
                val len = leInt(body, pos); pos += 4
                if (len < 0 || pos + len > body.size) return null
                val entry = body.copyOfRange(pos, pos + len); pos += len
                val eq = entry.indexOf(0x3D) // '='
                if (eq > 0) {
                    val field = String(entry, 0, eq, StandardCharsets.US_ASCII).uppercase()
                    if (field == "LYRICS" || field == "UNSYNCEDLYRICS") { removed = true; continue }
                }
                kept.add(entry)
            }
            if (!removed) return null

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

    private fun removeMp3Lyrics(file: File): Boolean {
        val tag = "LyricsEmbedder"
        val raf = RandomAccessFile(file, "r")
        try {
            val first3 = ByteArray(3)
            if (raf.length() >= 3) raf.read(first3)
            val hasId3v2 = first3[0] == 0x49.toByte() && first3[1] == 0x44.toByte() && first3[2] == 0x33.toByte()
            if (!hasId3v2 || raf.length() < 10) { android.util.Log.d(tag, "mp3: no ID3v2, nothing to remove"); return false }

            val sizeBytes = ByteArray(4)
            raf.seek(6); raf.read(sizeBytes)
            val id3Size = synchSafeInt(sizeBytes[0], sizeBytes[1], sizeBytes[2], sizeBytes[3]) + 10
            if (id3Size <= 10 || id3Size > 262144 || id3Size > raf.length()) {
                android.util.Log.w(tag, "mp3: bad id3 size=$id3Size"); return false
            }
            raf.seek(0)
            val existingId3 = ByteArray(id3Size)
            raf.readFully(existingId3)

            val major = existingId3[3].toInt() and 0xFF
            if (major == 2) {
                android.util.Log.w(tag, "ID3v2.2 帧布局不同，拒绝改写以免破坏标签")
                return false
            }

            val stripped = stripId3Frame(existingId3, "USLT", major == 4)
            if (stripped.size == existingId3.size) { android.util.Log.d(tag, "mp3: no USLT, nothing to remove"); return false }
            val audioStart = id3Size.toLong()

            val tempFile = File(file.absolutePath + ".lyrtmp")
            try {
                FileOutputStream(tempFile).use { out ->
                    if (stripped.size > 10) {
                        val newTagSize = stripped.size - 10
                        val tagHeader = ByteArray(10)
                        tagHeader[0] = 'I'.code.toByte(); tagHeader[1] = 'D'.code.toByte(); tagHeader[2] = '3'.code.toByte()
                        tagHeader[3] = major.toByte(); tagHeader[4] = 0; tagHeader[5] = 0
                        writeSynchSafeInt(tagHeader, 6, newTagSize)
                        out.write(tagHeader)
                        out.write(stripped, 10, stripped.size - 10)
                    }
                    // stripped.size == 10 → USLT 是唯一帧：整个 ID3v2 块移除，音频体直接从 audioStart 接上
                    raf.seek(audioStart)
                    val buf = ByteArray(65536)
                    var n: Int
                    while (raf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
                raf.close()
                if (!replaceFile(tempFile, file)) {
                    android.util.Log.w(tag, "renameTo failed: ${tempFile.absolutePath}")
                    tempFile.delete()
                    return false
                }
                android.util.Log.d(tag, "mp3 removeLyrics OK: ${file.name}")
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

            // [V8.96] 保留原 ID3v2 主版本。v2.4 的帧长字段是 synchsafe（7bit/字节），
            // 旧代码一律按 v2.3 的 plain 32bit 写回 -- 遇到 v2.4 源会「用 v2.3 尺寸解释 v2.4 帧」，
            // 直接破坏已有标签；v2.2 帧布局（3 字节 ID + 3 字节长度）更是从根上不同。
            val major = existingId3?.let { it[3].toInt() and 0xFF } ?: 3
            if (existingId3 != null && major == 2) {
                android.util.Log.w("LyricsEmbedder", "ID3v2.2 帧布局不同，拒绝改写以免破坏标签")
                return false
            }
            val usltBody = buildUsltFrame(lyrics, major == 4)
            var newTagSize: Int
            var tagHeader: ByteArray

            if (existingId3 != null && existingId3.size > 10) {
                val stripped = stripId3Frame(existingId3, "USLT", major == 4)
                newTagSize = (stripped.size - 10) + usltBody.size
                tagHeader = ByteArray(10)
                tagHeader[0] = 'I'.code.toByte(); tagHeader[1] = 'D'.code.toByte(); tagHeader[2] = '3'.code.toByte()
                tagHeader[3] = major.toByte(); tagHeader[4] = 0; tagHeader[5] = 0
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
                    if (!replaceFile(tempFile, file)) { tempFile.delete(); return false }
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
                    if (!replaceFile(tempFile, file)) { tempFile.delete(); return false }
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

    /** 构建 USLT 帧（Unsynced Lyrics，UTF-8）。synchsafeSize=true 时按 ID3v2.4 写帧长。 */
    private fun buildUsltFrame(lyrics: String, synchsafeSize: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x03) // text encoding = UTF-8
        body.write("XXX".toByteArray(StandardCharsets.US_ASCII)) // language
        body.write(0x00) // content descriptor = 空，null 终止
        body.write(lyrics.toByteArray(StandardCharsets.UTF_8))
        val bodyBytes = body.toByteArray()

        val frame = ByteArrayOutputStream()
        frame.write("USLT".toByteArray(StandardCharsets.US_ASCII))
        frame.write(if (synchsafeSize) {
            // ID3v2.4：帧长 synchsafe（每字节仅低 7 位有效）
            byteArrayOf(
                ((bodyBytes.size shr 21) and 0x7F).toByte(),
                ((bodyBytes.size shr 14) and 0x7F).toByte(),
                ((bodyBytes.size shr 7) and 0x7F).toByte(),
                (bodyBytes.size and 0x7F).toByte()
            )
        } else {
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bodyBytes.size).array()
        })
        frame.write(ByteArray(2)) // flags
        frame.write(bodyBytes)
        return frame.toByteArray()
    }

    private fun stripId3Frame(tag: ByteArray, frameId: String, synchsafeSize: Boolean): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(tag, 0, 10)
        var pos = 10
        while (pos + 10 <= tag.size) {
            if (tag[pos] == 0.toByte()) break
            val size = if (synchsafeSize) {
                ((tag[pos + 4].toInt() and 0x7F) shl 21) or
                    ((tag[pos + 5].toInt() and 0x7F) shl 14) or
                    ((tag[pos + 6].toInt() and 0x7F) shl 7) or (tag[pos + 7].toInt() and 0x7F)
            } else {
                ((tag[pos + 4].toInt() and 0xFF) shl 24) or
                    ((tag[pos + 5].toInt() and 0xFF) shl 16) or
                    ((tag[pos + 6].toInt() and 0xFF) shl 8) or (tag[pos + 7].toInt() and 0xFF)
            }
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

    // ============ [V8.129] WAV（RIFF） ============

    /**
     * 把歌词写进 WAV 的 "id3 " chunk（完整 ID3v2.3 标签 + USLT 帧）。
     *
     * 与读取端 WavCoverReader.acquireId3Blob 对称：id3 chunk 里放的是「完整 ID3v2 标签」
     * （含 10 字节标签头）。算法：流式重写整个 RIFF —— 丢弃旧的 id3 chunk，其余 chunk
     * （含可能很大的 data）按 64KB 原样搬运，最后追加新 id3 chunk；再回填 RIFF size。
     * 音频负载不进内存；写 .lyrtmp → 原子 rename；失败返回 false，不影响 .lrc 保存。
     * RF64/BW64（64 位尺寸）与 data 尺寸为 0xFFFFFFFF 的流式文件一律拒绝，避免写坏。
     */
    private fun embedWavLyrics(file: File, lyrics: String): Boolean {
        val tag = "LyricsEmbedder"
        val raf = RandomAccessFile(file, "r")
        try {
            if (raf.length() < 12L) { android.util.Log.w(tag, "wav too small"); return false }
            val head = ByteArray(12)
            raf.readFully(head)
            val container = String(head, 0, 4, StandardCharsets.US_ASCII)
            if (container != "RIFF") {
                // RF64 / BW64 用 64 位尺寸字段，本写入器不支持（宁可不动也不要写坏）
                android.util.Log.w(tag, "wav: unsupported container=$container")
                return false
            }
            if (String(head, 8, 4, StandardCharsets.US_ASCII) != "WAVE") return false

            // [V8.137] 保留原 id3 chunk 里的全部帧（封面 APIC、标题、歌手…），只替换歌词帧。
            // 旧实现 buildWavId3Chunk 会新建一个只含 USLT 的标签，把封面连同其它标签一起丢弃
            // —— 这正是「保存内嵌歌词后歌曲封面被删」的根因。
            val existingTag = readExistingWavId3Tag(raf)
            val id3Chunk = buildWavId3ChunkWithLyrics(existingTag, lyrics)
            val tempFile = File(file.absolutePath + ".lyrtmp")
            try {
                FileOutputStream(tempFile).use { out ->
                    out.write(head, 0, 12) // RIFF size 稍后回填
                    if (!copyRiffChunksExceptId3(raf, out)) {
                        android.util.Log.w(tag, "wav: chunk walk failed")
                        out.flush()
                        tempFile.delete()
                        return false
                    }
                    out.write(id3Chunk)
                }
                if (!fixRiffSize(tempFile)) { tempFile.delete(); return false }
                raf.close()
                if (!replaceFile(tempFile, file)) {
                    android.util.Log.w(tag, "renameTo failed: ${tempFile.absolutePath}")
                    tempFile.delete()
                    return false
                }
                android.util.Log.d(tag, "wav embed OK: ${file.name}")
                return true
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /** 移除 WAV 内嵌歌词：流式重写，丢弃 "id3 " chunk，其余原样保留。 */
    private fun removeWavLyrics(file: File): Boolean {
        val tag = "LyricsEmbedder"
        val raf = RandomAccessFile(file, "r")
        try {
            if (raf.length() < 12L) return false
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, StandardCharsets.US_ASCII) != "RIFF" ||
                String(head, 8, 4, StandardCharsets.US_ASCII) != "WAVE"
            ) return false

            val tempFile = File(file.absolutePath + ".lyrtmp")
            var removed = false
            try {
                FileOutputStream(tempFile).use { out ->
                    out.write(head, 0, 12)
                    // [V8.137] 不再整块丢弃 id3 chunk（里面还有封面 APIC 等），
                    // 改为只剥掉歌词帧，保留其余标签；无其它帧时才丢整块。
                    val r = copyRiffChunksStrippingLyrics(raf, out) { removed = true }
                    if (!r) { out.flush(); tempFile.delete(); return false }
                }
                if (!removed) {
                    android.util.Log.d(tag, "wav: no id3 chunk, nothing to remove")
                    tempFile.delete()
                    return false
                }
                if (!fixRiffSize(tempFile)) { tempFile.delete(); return false }
                raf.close()
                if (!replaceFile(tempFile, file)) { tempFile.delete(); return false }
                android.util.Log.d(tag, "wav removeLyrics OK: ${file.name}")
                return true
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /**
     * V8.137 逐 chunk 搬运，遇到 "id3 " chunk 时只剥掉其中的 USLT 歌词帧，
     * 其余帧（封面 APIC、标题、歌手…）原样保留；剥完后标签为空才整块丢弃。
     * onStripped 仅在真的移除了歌词时回调。返回 false 表示无法安全处理，调用方必须放弃写入。
     */
    private fun copyRiffChunksStrippingLyrics(
        raf: RandomAccessFile,
        out: FileOutputStream,
        onStripped: () -> Unit
    ): Boolean {
        val total = raf.length()
        var offset = 12L
        val buf = ByteArray(65536)

        while (offset + 8L <= total) {
            raf.seek(offset)
            val ch = ByteArray(8)
            raf.readFully(ch)
            val id = String(ch, 0, 4, StandardCharsets.US_ASCII)
            val rawSize = leUInt(ch, 4)
            if (rawSize == 0xFFFFFFFFL) return false
            if (offset + 8L + rawSize > total) return false

            if (id == "id3 " || id == "ID3 " || id == "iD3 ") {
                // 读标签 -> 剥歌词帧 -> 仍有内容就回写新 chunk，否则丢弃
                var newTag: ByteArray? = null
                if (rawSize in 10L..(8L * 1024L * 1024L)) {
                    raf.seek(offset + 8L)
                    val blob = ByteArray(rawSize.toInt())
                    raf.readFully(blob)
                    val major = blob[3].toInt() and 0xFF
                    val stripped = stripId3Frame(blob, "USLT", synchsafeSize = (major == 4))
                    val body = stripped.copyOfRange(10, stripped.size)
                    if (stripped.size != blob.size) {
                        onStripped()
                        if (body.isNotEmpty()) {
                            val id3 = ByteArray(10 + body.size)
                            id3[0] = 'I'.code.toByte(); id3[1] = 'D'.code.toByte(); id3[2] = '3'.code.toByte()
                            id3[3] = blob[3]; id3[4] = 0; id3[5] = 0
                            writeSynchSafeInt(id3, 6, body.size)
                            System.arraycopy(body, 0, id3, 10, body.size)
                            newTag = id3
                        }
                    }
                }
                if (newTag != null) {
                    val pad = if (newTag.size and 1 == 1) 1 else 0
                    val newCh = ByteArray(8 + newTag.size + pad)
                    newCh[0] = 'i'.code.toByte(); newCh[1] = 'd'.code.toByte()
                    newCh[2] = '3'.code.toByte(); newCh[3] = ' '.code.toByte()
                    writeLeInt(newCh, 4, newTag.size)
                    System.arraycopy(newTag, 0, newCh, 8, newTag.size)
                    out.write(newCh)
                }
                offset += 8L + rawSize + (rawSize and 1L)
                continue
            }

            out.write(ch)
            raf.seek(offset + 8L)
            var remaining = rawSize
            while (remaining > 0L) {
                val want = minOf(remaining, buf.size.toLong()).toInt()
                val r = raf.read(buf, 0, want)
                if (r <= 0) return false
                out.write(buf, 0, r)
                remaining -= r.toLong()
            }
            if (rawSize and 1L == 1L) out.write(0)
            offset += 8L + rawSize + (rawSize and 1L)
        }
        return true
    }

    /**
     * 从 raf 当前位置（12，RIFF 头之后）逐 chunk 搬运到 out，跳过 "id3 "/"ID3 "；
     * 遇到无法安全处理的尺寸（0 / 超文件 / 0xFFFFFFFF 流式）返回 false（调用方必须放弃写入）。
     */
    private fun copyRiffChunksExceptId3(
        raf: RandomAccessFile,
        out: FileOutputStream,
        onSkipped: (() -> Unit)? = null
    ): Boolean {
        val total = raf.length()
        var offset = 12L
        val buf = ByteArray(65536)

        while (offset + 8L <= total) {
            raf.seek(offset)
            val ch = ByteArray(8)
            raf.readFully(ch)
            val id = String(ch, 0, 4, StandardCharsets.US_ASCII)
            val rawSize = leUInt(ch, 4)
            // 0 = 空 chunk（合法但无意义）；0xFFFFFFFF = 流式尺寸，无法安全重写
            if (rawSize == 0xFFFFFFFFL) return false
            if (offset + 8L + rawSize > total) return false

            if (id == "id3 " || id == "ID3 " || id == "iD3 ") {
                onSkipped?.invoke()
                offset += 8L + rawSize + (rawSize and 1L)
                continue
            }

            out.write(ch)
            raf.seek(offset + 8L)
            var remaining = rawSize
            while (remaining > 0L) {
                val want = minOf(remaining, buf.size.toLong()).toInt()
                val r = raf.read(buf, 0, want)
                if (r <= 0) return false
                out.write(buf, 0, r)
                remaining -= r.toLong()
            }
            if (rawSize and 1L == 1L) out.write(0) // chunk 奇数长度补 1 字节对齐
            offset += 8L + rawSize + (rawSize and 1L)
        }
        return true
    }

    /** 构建完整 "id3 " chunk：8 字节 chunk 头 + 完整 ID3v2.3 标签（内含 USLT）+ 对齐补位。 */
    private fun buildWavId3Chunk(lyrics: String): ByteArray {
        val uslt = buildUsltFrame(lyrics, synchsafeSize = false)
        val id3 = ByteArray(10 + uslt.size)
        id3[0] = 'I'.code.toByte(); id3[1] = 'D'.code.toByte(); id3[2] = '3'.code.toByte()
        id3[3] = 3; id3[4] = 0; id3[5] = 0
        writeSynchSafeInt(id3, 6, uslt.size)
        System.arraycopy(uslt, 0, id3, 10, uslt.size)

        val pad = if (id3.size and 1 == 1) 1 else 0
        val chunk = ByteArray(8 + id3.size + pad)
        chunk[0] = 'i'.code.toByte(); chunk[1] = 'd'.code.toByte()
        chunk[2] = '3'.code.toByte(); chunk[3] = ' '.code.toByte()
        writeLeInt(chunk, 4, id3.size)
        System.arraycopy(id3, 0, chunk, 8, id3.size)
        return chunk
    }

    /**
     * V8.137 读取 RIFF 中已存在的 "id3 " chunk 的完整 ID3v2 标签（无则返回 null）。
     * 只 seek + 读该 chunk，不搬运音频体。异常/超大标签一律返回 null（交由调用方新建标签）。
     */
    private fun readExistingWavId3Tag(raf: RandomAccessFile): ByteArray? {
        return try {
            val total = raf.length()
            var offset = 12L
            val ch = ByteArray(8)
            while (offset + 8L <= total) {
                raf.seek(offset)
                raf.readFully(ch)
                val id = String(ch, 0, 4, StandardCharsets.US_ASCII)
                val rawSize = leUInt(ch, 4)
                if (rawSize == 0xFFFFFFFFL) return null
                if (offset + 8L + rawSize > total) return null
                if (id == "id3 " || id == "ID3 " || id == "iD3 ") {
                    // 标签异常小/超大（>8MB）不保留，退回新建，避免误解析
                    if (rawSize < 10L || rawSize > 8L * 1024L * 1024L) return null
                    raf.seek(offset + 8L)
                    val blob = ByteArray(rawSize.toInt())
                    raf.readFully(blob)
                    return blob
                }
                offset += 8L + rawSize + (rawSize and 1L)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * V8.137 构建 "id3 " chunk，保留 existing 里的全部帧，只替换/追加歌词帧。
     * 与 buildWavId3Chunk 的区别：后者新建「只含 USLT」的标签，会丢掉封面 APIC 等，
     * 是「保存内嵌歌词把封面删掉」的根因。existing == null 时行为等价于新建。
     */
    private fun buildWavId3ChunkWithLyrics(existing: ByteArray?, lyrics: String): ByteArray {
        val uslt = buildUsltFrame(lyrics, synchsafeSize = false)
        val id3: ByteArray
        if (existing != null && existing.size > 10) {
            // 帧长格式跟随原标签主版本：v2.4 为 synchsafe，其余按 v2.3 plain 32bit
            val major = existing[3].toInt() and 0xFF
            val stripped = stripId3Frame(existing, "USLT", synchsafeSize = (major == 4))
            val body = stripped.copyOfRange(10, stripped.size)
            id3 = ByteArray(10 + body.size + uslt.size)
            id3[0] = 'I'.code.toByte(); id3[1] = 'D'.code.toByte(); id3[2] = '3'.code.toByte()
            id3[3] = existing[3]; id3[4] = 0; id3[5] = 0
            writeSynchSafeInt(id3, 6, body.size + uslt.size)
            System.arraycopy(body, 0, id3, 10, body.size)
            System.arraycopy(uslt, 0, id3, 10 + body.size, uslt.size)
        } else {
            id3 = ByteArray(10 + uslt.size)
            id3[0] = 'I'.code.toByte(); id3[1] = 'D'.code.toByte(); id3[2] = '3'.code.toByte()
            id3[3] = 3; id3[4] = 0; id3[5] = 0
            writeSynchSafeInt(id3, 6, uslt.size)
            System.arraycopy(uslt, 0, id3, 10, uslt.size)
        }

        val pad = if (id3.size and 1 == 1) 1 else 0
        val chunk = ByteArray(8 + id3.size + pad)
        chunk[0] = 'i'.code.toByte(); chunk[1] = 'd'.code.toByte()
        chunk[2] = '3'.code.toByte(); chunk[3] = ' '.code.toByte()
        writeLeInt(chunk, 4, id3.size)
        System.arraycopy(id3, 0, chunk, 8, id3.size)
        return chunk
    }

    /** 回填 RIFF size 字段 = 文件总长 - 8。 */
    private fun fixRiffSize(f: File): Boolean = try {
        RandomAccessFile(f, "rw").use { t ->
            val sz = ByteArray(4)
            writeLeInt(sz, 0, (t.length() - 8L).toInt())
            t.seek(4)
            t.write(sz)
        }
        true
    } catch (e: Exception) {
        android.util.Log.w("LyricsEmbedder", "fixRiffSize failed: ${e.message}")
        false
    }

    private fun writeLeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun leUInt(buf: ByteArray, offset: Int): Long =
        (buf[offset].toLong() and 0xFF) or
            ((buf[offset + 1].toLong() and 0xFF) shl 8) or
            ((buf[offset + 2].toLong() and 0xFF) shl 16) or
            ((buf[offset + 3].toLong() and 0xFF) shl 24)
}
