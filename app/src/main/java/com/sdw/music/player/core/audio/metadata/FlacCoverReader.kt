package com.sdw.music.player.core.audio.metadata
import com.sdw.music.player.core.audio.metadata.FlacCoverReader
import com.sdw.music.player.core.audio.metadata.WavCoverReader

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * V8.138 FLAC embedded cover reader - plan B for FLAC.
 *
 * WHY THIS EXISTS
 * Android's MediaStore serves a picture for MP3/FLAC only through the
 * content://media/external/audio/albumart/<albumId> virtual table, which is populated by the
 * platform media scanner from the file's embedded art. That index is keyed to the file row
 * (size / mtime). When we rewrite an audio file to change its LYRICS tag (embedded lyric
 * editing) the whole file is rewritten (len + mtime change) but the media library is never
 * told, so the albumart row goes stale and returns nothing - the UI then only shows the
 * fallback logo, which looks exactly like "the cover was deleted".
 *
 * The image itself is still inside the file: for FLAC it lives in a METADATA_BLOCK_PICTURE
 * (block type 6) after the "fLaC" magic and the STREAMINFO block. This reader pulls it out
 * directly, so the cover no longer depends on the platform index at all. It mirrors
 * WavCoverReader for WAV.
 *
 * Pure Kotlin. No native code, no MediaMetadataRetriever, unit-testable offline.
 * Read-only: the audio file is never modified.
 */
object FlacCoverReader {

    private const val MAX_META_BYTES = 32 * 1024 * 1024
    private const val MAX_PICTURE_BYTES = 24 * 1024 * 1024

    /** A successfully extracted embedded image. */
    class Cover(val bytes: ByteArray, val mime: String)

    // ---------------------------------------------------------------- public API

    /** Extract the embedded cover from [filePath], or null when there is none. */
    fun readCover(filePath: String): Cover? {
        val file = File(filePath)
        if (!file.isFile || file.length() < 42L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (!hasFlacMagic(raf)) return null
                walkMetadataBlocks(raf)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolve the cover URI for a FLAC file, using the private on-disk cache when possible.
     *
     * Returns null when the file has no embedded cover, in which case the caller should keep
     * whatever URI it already had. Cache is keyed by (path, size, mtime) so a rewritten file
     * misses and is re-read, while repeat scans / heals are a single stat() call each.
     */
    fun coverUriForFile(context: Context, filePath: String): String? {
        val file = File(filePath)
        if (!file.isFile || file.length() < 42L) return null
        val key = cacheKey(filePath, file.length(), file.lastModified())
        CoverArtStore.uriFor(context, key)?.let { return it }
        if (CoverArtStore.isKnownMissing(context, key)) return null
        val cover = readCover(filePath)
        if (cover == null) {
            CoverArtStore.markMissing(context, key)
            return null
        }
        return CoverArtStore.store(context, key, cover.bytes)
    }

    // ---------------------------------------------------------------- FLAC walking

    private fun hasFlacMagic(raf: RandomAccessFile): Boolean {
        val magic = ByteArray(4)
        raf.seek(0)
        raf.readFully(magic)
        return magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
            magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte()
    }

    /**
     * Walk the FLAC metadata block list looking for a PICTURE block (type 6) and, failing
     * that, a base64 picture carried inside the VORBIS_COMMENT block (type 4).
     *
     * Metadata blocks: 1 header byte (bit7 = last-block flag, bits0-6 = type) + 3 bytes
     * big-endian length + payload. The walk stops as soon as a picture is found so a 200MB
     * FLAC costs only the header bytes plus the picture itself.
     */
    private fun walkMetadataBlocks(raf: RandomAccessFile): Cover? {
        val total = raf.length()
        var offset = 4L
        var vorbisBlock: ByteArray? = null
        var guard = 0
        while (offset + 4L <= total && guard++ < 4096) {
            raf.seek(offset)
            val header = ByteArray(4)
            raf.readFully(header)
            val last = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val len = ((header[1].toLong() and 0xFF) shl 16) or
                ((header[2].toLong() and 0xFF) shl 8) or
                (header[3].toLong() and 0xFF)
            if (len < 0L || len > MAX_META_BYTES.toLong()) return null
            val payloadStart = offset + 4L
            if (payloadStart + len > total) return null

            if (type == 6) {
                val lenI = len.toInt()
                val body = ByteArray(lenI)
                raf.readFully(body)
                parsePictureBlock(body)?.let { return it }
            } else if (type == 4 && vorbisBlock == null) {
                val lenI = len.toInt()
                val body = ByteArray(lenI)
                raf.readFully(body)
                vorbisBlock = body
            }

            if (last) break
            offset = payloadStart + len
        }
        // No PICTURE block: some encoders stash the cover as base64 inside VORBIS_COMMENT.
        vorbisBlock?.let { return parseVorbisPicture(it) }
        return null
    }

    /**
     * Parse a FLAC PICTURE block payload:
     *   32-bit BE picture type
     *   32-bit BE MIME length + MIME string (US-ASCII)
     *   32-bit BE description length + description (UTF-8)
     *   32-bit BE width, height, color depth, colors used
     *   32-bit BE data length + picture data
     */
    private fun parsePictureBlock(blob: ByteArray): Cover? {
        try {
            var p = 0
            if (blob.size < 8) return null
            p += 4 // picture type (3 = front cover, but any type is acceptable here)
            val mimeLen = beInt(blob, p); p += 4
            if (mimeLen < 0 || p + mimeLen > blob.size) return null
            val mime = String(blob, p, mimeLen, StandardCharsets.US_ASCII).trim()
            p += mimeLen
            val descLen = beInt(blob, p); p += 4
            if (descLen < 0 || p + descLen > blob.size) return null
            p += descLen
            if (p + 16 > blob.size) return null
            p += 16 // width, height, depth, colors
            val dataLen = beInt(blob, p); p += 4
            if (dataLen <= 0 || dataLen > MAX_PICTURE_BYTES) return null
            if (p + dataLen > blob.size) return null
            val data = blob.copyOfRange(p, p + dataLen)
            if (data.isEmpty()) return null
            return Cover(data, sniffMime(data, mime))
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Fallback for VORBIS_COMMENT holders. Two conventions exist:
     *   METADATA_BLOCK_PICTURE=<base64 of a whole PICTURE block payload>
     *   COVERART=<base64 of the raw image bytes>
     * Both are decoded and handed to the same parser / sniffer.
     */
    private fun parseVorbisPicture(vc: ByteArray): Cover? {
        try {
            val text = String(vc, StandardCharsets.ISO_8859_1)
            for (field in listOf("METADATA_BLOCK_PICTURE=", "COVERART=")) {
                val idx = text.indexOf(field, ignoreCase = true)
                if (idx < 0) continue
                var end = text.indexOf('\u0000', idx)
                if (end < 0) end = text.length
                val raw = text.substring(idx + field.length, end).trim()
                if (raw.isEmpty()) continue
                val decoded = try {
                    Base64.decode(raw, Base64.DEFAULT)
                } catch (e: Exception) {
                    continue
                }
                if (decoded.isEmpty()) continue
                if (field.startsWith("METADATA")) {
                    parsePictureBlock(decoded)?.let { return it }
                }
                val mime = sniffMime(decoded, "")
                if (mime.startsWith("image/")) return Cover(decoded, mime)
            }
        } catch (e: Exception) {
            // fall through
        }
        return null
    }

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** Identify the image type from its magic; [declared] wins when it is a real image MIME. */
    private fun sniffMime(data: ByteArray, declared: String): String {
        if (declared.startsWith("image/", ignoreCase = true)) return declared.lowercase()
        if (data.size >= 3 && (data[0].toInt() and 0xFF) == 0xFF &&
            (data[1].toInt() and 0xFF) == 0xD8
        ) return "image/jpeg"
        if (data.size >= 8 && (data[0].toInt() and 0xFF) == 0x89 &&
            data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()
        ) return "image/png"
        if (data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte()
        ) return "image/webp"
        if (data.size >= 6 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte()
        ) return "image/gif"
        return if (declared.isNotBlank()) declared.lowercase() else "image/jpeg"
    }

    private fun cacheKey(path: String, length: Long, mtime: Long): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest("$path|$length|$mtime".toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) sb.append(String.format("%02x", byte))
        return sb.toString()
    }
}
