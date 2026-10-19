package com.sdw.music.player.core.audio.metadata
import com.sdw.music.player.SongRepository
import com.sdw.music.player.core.audio.metadata.WavCoverReader

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * V8.87 WAV embedded cover reader - plan B.
 *
 * WHY THIS EXISTS
 * Android's WAV extractor never reports album art. AOSP WAVExtractor::getMetaData() only
 * sets kKeyMIMEType (MEDIA_MIMETYPE_CONTAINER_WAV) and never sets kKeyAlbumArt, and its
 * init() walks the RIFF chunk list handling ONLY "fmt " (plus "data" for the duration) -
 * every other chunk, including "id3 ", is skipped with a plain offset += chunkSize.
 *
 * MediaMetadataRetriever.getEmbeddedPicture() resolves through that same kKeyAlbumArt
 * lookup, so for WAV it always returns null ("getEmbeddedPicture: Call to
 * getEmbeddedPicture failed.") even when the file genuinely carries a cover. Plan A
 * (MediaMetadataRetriever) therefore can never fix WAV - only a container-level parser can.
 *
 * WHAT WE PARSE
 * A WAV is a RIFF container. The only chunk that can hold a picture is "id3 " (note the
 * trailing space - the Adobe / Wavelab convention), which carries a complete ID3v2 tag
 * whose APIC frame holds the image. The RIFF-native "LIST" -> "INFO" metadata block is
 * TEXT ONLY (INAM/IART/IPRD/...); the spec defines no picture field there, so a WAV whose
 * cover was written as RIFF INFO simply has no cover to read.
 *
 * We also accept a bare ID3v2 tag prepended before the "RIFF" header, which some encoders
 * emit.
 *
 * Pure Kotlin. No native code, no MediaMetadataRetriever, unit-testable offline.
 * Read-only: the audio file is never modified.
 */
object WavCoverReader {

    private const val MAX_ID3_BYTES = 16 * 1024 * 1024

    /** A successfully extracted embedded image. */
    class Cover(val bytes: ByteArray, val mime: String)

    /**
     * V8.91 Text tags carried by the embedded ID3v2 tag.
     *
     * WAV is the one format where the platform index cannot be trusted: AOSP's WAVExtractor
     * skips every chunk except "fmt " / "data", so MediaStore ends up with the FILE NAME as
     * the title, no artist at all, and the containing FOLDER as the album. The tags the
     * creator actually wrote live in the "id3 " chunk, so we read them straight from there.
     *
     * Empty string means "the tag did not carry that field" - callers must keep whatever
     * value they already had rather than blanking it out.
     */
    class EmbeddedTags(
        var title: String = "",
        var artist: String = "",
        var album: String = "",
        var albumArtist: String = "",
        var track: String = "",
        var genre: String = "",
        var year: String = "",
        var lyrics: String = ""
    ) {
        /** True when at least one of the three fields we surface in the UI is present. */
        fun hasCore(): Boolean =
            title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()
    }

    // ---------------------------------------------------------------- public API

    /**
     * Extract the embedded cover from [filePath], or null when there is none.
     *
     * Typical callers: the MediaStore scan and the one-shot heal pass in SongRepository,
     * both of which run on Dispatchers.IO.
     */
    fun readCover(filePath: String): Cover? {
        val file = File(filePath)
        if (!file.isFile || file.length() < 12L) return null
        return try {
            acquireId3Blob(file)?.let { parseId3(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * V8.91 Read the text tags (title / artist / album / lyrics) from the embedded ID3v2 tag.
     *
     * Same container walk as readCover - the tags and the picture live in the SAME "id3 "
     * chunk, so both readers share acquireId3Blob. Returns null when the file carries no
     * ID3v2 tag at all; a tag that exists but has no text frames is reported as null too, so
     * callers never blank out a value they already had.
     *
     * Read-only: the audio file is never modified. Safe to call from Dispatchers.IO.
     */
    fun readTags(filePath: String): EmbeddedTags? {
        val file = File(filePath)
        if (!file.isFile || file.length() < 12L) return null
        return try {
            // ID3v2 (the "id3 " chunk, or a bare tag before/after the RIFF header) is the
            // authoritative source when present.
            val id3 = acquireId3Blob(file)?.let { parseTextTags(it) }
            // RIFF-native LIST/INFO is the OTHER common WAV tag home: Windows Explorer,
            // Sound Forge, ffmpeg and iTunes all write there (INAM/IART/IPRD/...). It is
            // the ONLY source when there is no id3 chunk, and a gap-filler otherwise. Without
            // it, every LIST/INFO-only WAV shows the file name instead of its real title.
            val info = readInfoListTags(file)
            if (id3 == null && info == null) return null
            val merged = id3 ?: EmbeddedTags()
            if (info != null) {
                if (merged.title.isBlank() && info.title.isNotBlank()) merged.title = info.title
                if (merged.artist.isBlank() && info.artist.isNotBlank()) merged.artist = info.artist
                if (merged.album.isBlank() && info.album.isNotBlank()) merged.album = info.album
                if (merged.albumArtist.isBlank() && info.albumArtist.isNotBlank()) merged.albumArtist = info.albumArtist
                if (merged.track.isBlank() && info.track.isNotBlank()) merged.track = info.track
                if (merged.genre.isBlank() && info.genre.isNotBlank()) merged.genre = info.genre
                if (merged.year.isBlank() && info.year.isNotBlank()) merged.year = info.year
                if (merged.lyrics.isBlank() && info.lyrics.isNotBlank()) merged.lyrics = info.lyrics
            }
            if (merged.hasCore()) merged else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- RIFF LIST/INFO

    /**
     * V8.240 Parse the RIFF LIST/INFO text metadata. Returns null when the file carries no
     * INFO list. LIST/INFO is text-only (INAM/IART/IPRD/...); the picture lives in "id3 ".
     */
    private fun readInfoListTags(file: File): EmbeddedTags? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val info = walkRiffForInfo(raf) ?: return null
                parseInfoList(info)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Walk the top-level RIFF chunk list; return the body of the "LIST"/"INFO" chunk. */
    private fun walkRiffForInfo(raf: RandomAccessFile): ByteArray? {
        val total = raf.length()
        if (total < 12L) return null
        val header = ByteArray(12)
        raf.readFully(header)
        val container = String(header, 0, 4, StandardCharsets.US_ASCII)
        if (container != "RIFF" && container != "RF64" && container != "BW64") return null
        if (String(header, 8, 4, StandardCharsets.US_ASCII) != "WAVE") return null

        var offset = 12L
        while (offset + 8L <= total) {
            raf.seek(offset)
            val ch = ByteArray(8)
            raf.readFully(ch)
            val id = String(ch, 0, 4, StandardCharsets.US_ASCII)
            val rawSize = le32(ch, 4)
            if (rawSize <= 0L) return null
            if (id == "LIST") {
                // LIST body starts with a 4-byte form-type ("INFO", "adtl", ...).
                raf.readFully(ch, 0, 4)
                val type = String(ch, 0, 4, StandardCharsets.US_ASCII)
                val bodyLen = (rawSize - 4).toInt().coerceAtLeast(0)
                if (type == "INFO" && bodyLen > 0) {
                    val body = ByteArray(bodyLen)
                    raf.readFully(body, 0, bodyLen)
                    return body
                }
            }
            // Chunks are word-aligned: an odd size is followed by one pad byte.
            offset += 8L + rawSize + (rawSize and 1L)
        }
        return null
    }

    /** Parse the INFO sub-chunk list (INAM/IART/IPRD/...) into EmbeddedTags. */
    private fun parseInfoList(body: ByteArray): EmbeddedTags? {
        val out = EmbeddedTags()
        var any = false
        var pos = 0
        while (pos + 8 <= body.size) {
            val id = String(body, pos, 4, StandardCharsets.US_ASCII)
            val size = le32(body, pos + 4).toInt()
            val dataStart = pos + 8
            if (size <= 0 || dataStart + size > body.size) break
            val dataEnd = dataStart + size
            val text = decodeInfoText(body, dataStart, dataEnd) ?: continue
            when (id) {
                "INAM" -> if (text.isNotBlank()) { out.title = text; any = true }
                "IART" -> if (text.isNotBlank()) { out.artist = text; any = true }
                "IPRD" -> if (text.isNotBlank()) { out.album = text; any = true }
                "IPRT", "ITRK" -> if (text.isNotBlank()) { out.track = text; any = true }
                "IGNR" -> if (text.isNotBlank()) { out.genre = text; any = true }
                "ICRD" -> if (text.isNotBlank()) { out.year = text; any = true }
                "ICMT" -> if (text.isNotBlank()) { out.lyrics = text; any = true }
                // IKEY (keywords) / ISRC / ISRF / IENG / etc. are not surfaced in the UI.
            }
            // Word-aligned: a child chunk with odd size carries one pad byte.
            pos = dataEnd + (size and 1)
        }
        return if (any) out else null
    }

    /**
     * Decode a RIFF INFO text value. INFO strings carry no encoding byte; the encodings seen
     * in the wild are: UTF-16LE/UTF-16BE (BOM-led, what Windows Explorer writes), UTF-8 (some
     * taggers), GBK (Chinese local-codepage files), or plain ASCII/ISO-8859-1. We try them in
     * that order, then trim the trailing NUL/space padding that RIFF word-alignment adds.
     */
    private fun decodeInfoText(buf: ByteArray, ds: Int, de: Int): String? {
        if (ds >= de) return null
        var end = de
        while (end > ds &&
            ((buf[end - 1].toInt() and 0xFF) == 0 || buf[end - 1] == ' '.code.toByte())
        ) end--
        if (end <= ds) return null
        return decodeInfoBytes(buf.copyOfRange(ds, end))
    }

    private fun decodeInfoBytes(b: ByteArray): String? {
        if (b.size >= 2) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return try { String(b, 2, b.size - 2, StandardCharsets.UTF_16LE) } catch (_: Exception) { null }
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return try { String(b, 2, b.size - 2, StandardCharsets.UTF_16BE) } catch (_: Exception) { null }
            }
        }
        // Strict UTF-8, but only when it carries non-ASCII (mirrors strictUtf8OrNull for ID3).
        val utf8 = strictUtf8OrNull(b)
        if (utf8 != null && utf8.any { it.code > 0x7F }) return utf8
        // Chinese WAV tags are frequently GBK (the Windows local codepage).
        if (hasHighByte(b)) {
            try {
                val gbk = String(b, charset("GBK"))
                if (gbk.isNotBlank() && !gbk.contains('\uFFFD')) return gbk
            } catch (_: Exception) { /* fall through */ }
        }
        return String(b, StandardCharsets.ISO_8859_1)
    }

    private fun hasHighByte(b: ByteArray): Boolean {
        for (x in b) if (x.toInt() and 0x80 != 0) return true
        return false
    }

    /**
     * Pull the raw ID3v2 tag out of a file: either a bare tag in front of the RIFF header,
     * an "id3 " chunk inside the RIFF chunk list, or a bare tag appended at EOF.
     * Shared by readCover and readTags.
     */
    private fun acquireId3Blob(file: File): ByteArray? =
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(3)
            raf.readFully(head)
            raf.seek(0)
            if (isId3(head)) {
                // A bare ID3v2 tag sitting in front of the RIFF header.
                return@use readId3Tag(raf)
            }
            // Normal RIFF/WAVE: walk the chunk list looking for "id3 ".
            walkRiffForId3Chunk(raf)
                // Some taggers append a bare ID3v2 tag at EOF instead of wrapping it in an
                // "id3 " chunk. The walk above sees those bytes as an unknown chunk header
                // and jumps past EOF, so scan the tail directly.
                ?: scanTailForId3(raf)
        }

    /**
     * Resolve the cover URI for a WAV file, using the private on-disk cache when possible.
     *
     * Returns null when the file has no embedded cover, in which case the caller should
     * keep whatever URI it already had.
     *
     * The cache is keyed by (path, size, mtime) so a changed file misses and is re-read,
     * while repeat scans and the heal pass are a single stat() call each.
     */
    fun coverUriForFile(context: Context, filePath: String): String? {
        val file = File(filePath)
        if (!file.isFile || file.length() < 12L) return null
        val key = cacheKey(filePath, file.length(), file.lastModified())
        CoverArtStore.uriFor(context, key)?.let { return it }
        // Negative cache: a WAV with no embedded cover must not be re-parsed on every
        // scan. The marker shares the key, so editing the file invalidates it too.
        if (CoverArtStore.isKnownMissing(context, key)) return null
        val cover = readCover(filePath)
        if (cover == null) {
            CoverArtStore.markMissing(context, key)
            return null
        }
        return CoverArtStore.store(context, key, cover.bytes)
    }

    // ---------------------------------------------------------------- RIFF walking

    /**
     * Walk the top-level RIFF chunk list and return the raw "id3 " chunk payload.
     *
     * Only 8-byte chunk headers are read for non-target chunks, so scanning a 200MB WAV
     * is still cheap: we seek past the audio payload rather than reading it.
     */
    private fun walkRiffForId3Chunk(raf: RandomAccessFile): ByteArray? {
        val total = raf.length()
        if (total < 12L) return null

        val header = ByteArray(12)
        raf.readFully(header)
        val container = String(header, 0, 4, StandardCharsets.US_ASCII)
        if (container != "RIFF" && container != "RF64" && container != "BW64") return null
        if (String(header, 8, 4, StandardCharsets.US_ASCII) != "WAVE") return null

        var offset = 12L
        while (offset + 8L <= total) {
            raf.seek(offset)
            val chunkHeader = ByteArray(8)
            raf.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
            // RIFF is little-endian.
            val rawSize = (chunkHeader[4].toLong() and 0xFF) or
                ((chunkHeader[5].toLong() and 0xFF) shl 8) or
                ((chunkHeader[6].toLong() and 0xFF) shl 16) or
                ((chunkHeader[7].toLong() and 0xFF) shl 24)
            if (rawSize <= 0L) return null

            if (id == "id3 " || id == "ID3 " || id == "iD3 ") {
                val len = rawSize.coerceAtMost(MAX_ID3_BYTES.toLong()).toInt()
                val body = ByteArray(len)
                raf.readFully(body, 0, len)
                // The chunk usually holds the full ID3v2 tag including its 10-byte header,
                // but some writers store only the body after the header. Both are handled
                // by parseId3: it re-checks the "ID3" magic and falls back to raw frames.
                return body
            }

            // Chunks are word-aligned: an odd size is followed by one pad byte.
            offset += 8L + rawSize + (rawSize and 1L)
        }
        return null
    }

    /**
     * Last-resort scan for an ID3v2 tag appended at the end of the file with no chunk
     * wrapper (a common tagger habit for WAV). Searches backwards so the trailing tag wins.
     *
     * False positives are harmless: a candidate only counts when the version/revision bytes
     * are plausible AND the declared body actually fits in the window, and parseId3 still
     * has to find a real APIC with image bytes before anything is returned.
     */
    private fun scanTailForId3(raf: RandomAccessFile): ByteArray? {
        val total = raf.length()
        if (total < 10L) return null
        val windowLen = minOf(total, MAX_ID3_BYTES.toLong() + 64L).toInt()
        if (windowLen < 10) return null
        val start = total - windowLen
        raf.seek(start)
        val buf = ByteArray(windowLen)
        raf.readFully(buf)

        var i = buf.size - 10
        while (i >= 0) {
            if (buf[i] == 'I'.code.toByte() &&
                buf[i + 1] == 'D'.code.toByte() &&
                buf[i + 2] == '3'.code.toByte()
            ) {
                val major = buf[i + 3].toInt() and 0xFF
                val revision = buf[i + 4].toInt() and 0xFF
                if (major in 2..4 && revision == 0) {
                    val size = syncSafeInt(buf, i + 6)
                    val tagEnd = i + 10 + size
                    if (size > 0 && tagEnd <= buf.size) {
                        return buf.copyOfRange(i, tagEnd)
                    }
                }
            }
            i--
        }
        return null
    }

    /** Read a full ID3v2 tag (10-byte header + declared body) from the current position. */
    private fun readId3Tag(raf: RandomAccessFile): ByteArray? {
        if (raf.length() < 10L) return null
        val header = ByteArray(10)
        raf.readFully(header)
        if (!isId3(header)) return null
        val declared = syncSafeInt(header, 6)
        if (declared <= 0) return null
        val bodyLen = declared.coerceAtMost(MAX_ID3_BYTES)
        val tag = ByteArray(10 + bodyLen)
        System.arraycopy(header, 0, tag, 0, 10)
        raf.readFully(tag, 10, bodyLen)
        return tag
    }

    // ---------------------------------------------------------------- ID3v2 parsing

    /**
     * Parse an ID3v2 tag and return its APIC (or v2.2 PIC) picture, if present.
     */
    private fun parseId3(b: ByteArray): Cover? {
        // A chunk that carries the bare frame body without the 10-byte tag header.
        if (!isId3(b)) return parseFrames(b, 0, b.size, 3)

        val major = b[3].toInt() and 0xFF
        val flags = b[5].toInt() and 0xFF
        val declared = syncSafeInt(b, 6)
        val bodyEnd = minOf(b.size, 10 + declared)
        if (bodyEnd <= 10) return null

        var body = b.copyOfRange(10, bodyEnd)
        // Whole-tag unsynchronisation: 0xFF 0x00 collapses back to 0xFF.
        if (flags and 0x80 != 0) body = deUnsynchronise(body)

        var pos = 0
        if (flags and 0x40 != 0) {
            // Extended header. v2.4 stores its own size syncsafe and INCLUSIVE of itself;
            // v2.3 stores a plain big-endian size EXCLUDING the 4 size bytes.
            pos = if (major >= 4) {
                val eh = syncSafeInt(body, 0)
                if (eh in 6..body.size) eh else return null
            } else {
                val eh = beInt(body, 0)
                if (eh < 0 || eh + 4 > body.size) return null
                eh + 4
            }
        }
        return parseFrames(body, pos, body.size, major)
    }

    /**
     * Walk the frame list, handing every frame to the visitor as (id, buffer, dataStart, dataEnd).
     *
     * The buffer is normally the array passed in; for a v2.4 frame with frame-level
     * unsynchronisation it is a de-stuffed copy, so callers must always read through the
     * buffer they are handed rather than the original array. The visitor returns true to stop
     * the walk (a non-local return, so callers can simply `return` their result).
     *
     * Shared by the cover path and the V8.91 text path so both agree on frame layout.
     */
    private inline fun forEachFrame(
        body: ByteArray,
        start: Int,
        end: Int,
        major: Int,
        visit: (id: String, buf: ByteArray, ds: Int, de: Int) -> Boolean
    ) {
        val idLen = if (major == 2) 3 else 4
        val sizeLen = if (major == 2) 3 else 4
        // v2.2 frames carry no flag bytes.
        val headerLen = if (major == 2) 6 else 10
        var pos = start

        while (pos + headerLen <= end) {
            // A zero byte at a frame boundary means padding starts here.
            if (body[pos] == 0.toByte()) break
            if (pos + idLen + sizeLen > end) break

            val id = String(body, pos, idLen, StandardCharsets.US_ASCII)
            val frameSize = when {
                major == 2 ->
                    ((body[pos + 3].toInt() and 0xFF) shl 16) or
                        ((body[pos + 4].toInt() and 0xFF) shl 8) or
                        (body[pos + 5].toInt() and 0xFF)
                major >= 4 -> syncSafeInt(body, pos + 4)
                else -> beInt(body, pos + 4)
            }
            val dataStart = pos + headerLen
            if (frameSize <= 0 || dataStart + frameSize > end) break
            var ds = dataStart
            val de = dataStart + frameSize

            if (major >= 4) {
                // v2.4 frame flags live in the last two header bytes.
                val frameFlags = (body[pos + 8].toInt() and 0xFF) or
                    ((body[pos + 9].toInt() and 0xFF) shl 8)
                // Data length indicator: 4 syncsafe bytes prepended to the body.
                if (frameFlags and 0x0001 != 0) ds += 4
                if (ds >= de) {
                    pos = de
                    continue
                }
                // Frame-level unsynchronisation. This matters for covers: JPEG data is full
                // of 0xFF bytes, so the stuffing would otherwise corrupt the image.
                if (frameFlags and 0x0002 != 0) {
                    val fixed = deUnsynchronise(body.copyOfRange(ds, de))
                    if (visit(id, fixed, 0, fixed.size)) return
                    pos = de
                    continue
                }
            }

            if (visit(id, body, ds, de)) return
            pos = de
        }
    }

    private fun parseFrames(body: ByteArray, start: Int, end: Int, major: Int): Cover? {
        forEachFrame(body, start, end, major) { id, buf, ds, de ->
            val isApic = (major >= 3 && id == "APIC") || (major == 2 && id == "PIC")
            if (isApic) {
                extractApic(buf, ds, de, major)?.let { return it }
            }
            false
        }
        return null
    }

    // ---------------------------------------------------------------- ID3v2 text tags

    /**
     * V8.91 Parse the text frames out of an ID3v2 tag.
     *
     * Handles the same three layouts as the cover path (bare frame body, normal tag, v2.2)
     * plus whole-tag unsynchronisation and the extended header. Returns null when no text
     * frame we care about is present.
     */
    private fun parseTextTags(blob: ByteArray): EmbeddedTags? {
        val out = EmbeddedTags()
        var any = false

        if (!isId3(blob)) {
            // A chunk carrying the bare frame body without the 10-byte tag header.
            forEachFrame(blob, 0, blob.size, 3) { id, buf, ds, de ->
                if (applyTextFrame(out, id, buf, ds, de)) any = true
                false
            }
            return if (any) out else null
        }

        val major = blob[3].toInt() and 0xFF
        val flags = blob[5].toInt() and 0xFF
        val declared = syncSafeInt(blob, 6)
        val bodyEnd = minOf(blob.size, 10 + declared)
        if (bodyEnd <= 10) return null

        var body = blob.copyOfRange(10, bodyEnd)
        if (flags and 0x80 != 0) body = deUnsynchronise(body)

        var pos = 0
        if (flags and 0x40 != 0) {
            // Same extended-header rules as parseId3: v2.4 syncsafe and inclusive,
            // v2.3 plain big-endian and exclusive of its own size bytes.
            pos = if (major >= 4) {
                val eh = syncSafeInt(body, 0)
                if (eh in 6..body.size) eh else return null
            } else {
                val eh = beInt(body, 0)
                if (eh < 0 || eh + 4 > body.size) return null
                eh + 4
            }
        }

        forEachFrame(body, pos, body.size, major) { id, buf, ds, de ->
            if (applyTextFrame(out, id, buf, ds, de)) any = true
            false
        }
        return if (any) out else null
    }

    /**
     * Map one frame onto [out]. v2.2 uses three-character ids (TT2/TP1/TAL/...), v2.3+ uses
     * four (TIT2/TPE1/TALB/...). First non-empty value wins, so a tag carrying both TPE1 and
     * TPE2 keeps the performer rather than the album artist.
     */
    private fun applyTextFrame(
        out: EmbeddedTags, id: String, buf: ByteArray, ds: Int, de: Int
    ): Boolean {
        if (ds >= de) return false
        when (id) {
            "TIT2", "TT2" -> readTextInto(buf, ds, de) { out.title = it }
            "TPE1", "TP1" -> readTextInto(buf, ds, de) { out.artist = it }
            "TPE2", "TP2" -> readTextInto(buf, ds, de) { out.albumArtist = it }
            "TALB", "TAL" -> readTextInto(buf, ds, de) { out.album = it }
            "TRCK", "TRK" -> readTextInto(buf, ds, de) { out.track = it }
            "TCON", "TCO" -> readTextInto(buf, ds, de) { out.genre = it }
            "TYER", "TYE", "TDRC" -> readTextInto(buf, ds, de) { out.year = it }
            "USLT", "ULT" -> {
                val text = decodeLyricFrame(buf, ds, de)
                if (!text.isNullOrBlank() && out.lyrics.isBlank()) out.lyrics = text
            }
            else -> return false
        }
        return true
    }

    private inline fun readTextInto(
        buf: ByteArray, ds: Int, de: Int, assign: (String) -> Unit
    ) {
        val text = decodeTextFrame(buf, ds, de) ?: return
        if (text.isBlank()) return
        assign(text)
    }

    /** Text frame body: encoding(1) followed by the encoded string. */
    private fun decodeTextFrame(buf: ByteArray, ds: Int, de: Int): String? {
        if (ds >= de) return null
        val enc = buf[ds].toInt() and 0xFF
        val raw = decodeEncoded(buf, ds + 1, de, enc) ?: return null
        return tidy(raw)
    }

    /**
     * USLT layout: encoding(1) | language(3) | descriptor (NUL-terminated) | lyrics.
     * UTF-16 descriptors terminate on a 16-bit NUL, single-byte encodings on one byte.
     */
    private fun decodeLyricFrame(buf: ByteArray, ds: Int, de: Int): String? {
        if (ds + 4 > de) return null
        val enc = buf[ds].toInt() and 0xFF
        var p = ds + 4
        if (enc == 1 || enc == 2) {
            while (p + 1 < de && !(buf[p] == 0.toByte() && buf[p + 1] == 0.toByte())) p++
            p += 2
        } else {
            while (p < de && buf[p] != 0.toByte()) p++
            p += 1
        }
        if (p >= de) return null
        val raw = decodeEncoded(buf, p, de, enc) ?: return null
        return tidy(raw)
    }

    /**
     * Decode according to the ID3 encoding byte: 0 = ISO-8859-1, 1 = UTF-16 with BOM,
     * 2 = UTF-16BE, 3 = UTF-8.
     *
     * Encoding byte 0 is the grey area: the spec says latin-1, but plenty of Chinese taggers
     * write UTF-8 (or GBK) and still label it 0. We only override latin-1 when the bytes are
     * STRICT valid UTF-8 carrying non-ASCII, which is a safe signal. GBK is deliberately not
     * guessed - a wrong guess mangles text silently, and our own writers always emit UTF-8
     * or UTF-16.
     */
    private fun decodeEncoded(buf: ByteArray, from: Int, to: Int, enc: Int): String? {
        if (from > to || from > buf.size) return null
        val body = buf.copyOfRange(from, minOf(to, buf.size))
        return try {
            when (enc) {
                0 -> strictUtf8OrNull(body) ?: String(body, StandardCharsets.ISO_8859_1)
                1 -> String(body, StandardCharsets.UTF_16)
                2 -> String(body, StandardCharsets.UTF_16BE)
                3 -> String(body, StandardCharsets.UTF_8)
                else -> String(body, StandardCharsets.ISO_8859_1)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Strict UTF-8 decode, but only when the bytes actually carry non-ASCII characters. */
    private fun strictUtf8OrNull(body: ByteArray): String? {
        var hasHigh = false
        for (b in body) {
            if (b.toInt() and 0x80 != 0) {
                hasHigh = true
                break
            }
        }
        if (!hasHigh) return null
        return try {
            val dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            dec.decode(java.nio.ByteBuffer.wrap(body)).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Drop NUL padding / surrounding whitespace / a stray BOM character. */
    private fun tidy(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isPad(s[start])) start++
        while (end > start && isPad(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun isPad(c: Char): Boolean = c <= ' ' || c == '\uFEFF'

    /**
     * APIC layout: encoding(1) | MIME\0 | picture type(1) | description(terminated) | data
     * v2.2 PIC swaps the MIME string for a fixed 3-char image format code.
     */
    private fun extractApic(b: ByteArray, start: Int, end: Int, major: Int): Cover? {
        if (start + 4 >= end) return null
        val encoding = b[start].toInt() and 0xFF
        var p = start + 1

        val declaredMime: String
        if (major == 2) {
            if (p + 3 > end) return null
            val fmt = String(b, p, 3, StandardCharsets.US_ASCII).trim().lowercase()
            declaredMime = if (fmt == "png") "image/png" else "image/jpeg"
            p += 3
        } else {
            val mimeEnd = indexOfZero(b, p, end)
            if (mimeEnd < 0) return null
            declaredMime = String(b, p, mimeEnd - p, StandardCharsets.ISO_8859_1)
            p = mimeEnd + 1
        }
        if (p >= end) return null
        p += 1 // picture type byte

        // The description is null-terminated; UTF-16 encodings terminate on a double zero.
        val descEnd = if (encoding == 1 || encoding == 2) indexOfDoubleZero(b, p, end)
        else indexOfZero(b, p, end)
        if (descEnd < 0) return null
        p = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (p >= end) return null

        val image = b.copyOfRange(p, end)
        if (image.size < 32) return null
        return Cover(image, sniffMime(image, declaredMime))
    }

    /** Trust the magic bytes over the declared MIME when they disagree. */
    private fun sniffMime(image: ByteArray, declared: String): String {
        if (image.size >= 3 &&
            (image[0].toInt() and 0xFF) == 0xFF &&
            (image[1].toInt() and 0xFF) == 0xD8 &&
            (image[2].toInt() and 0xFF) == 0xFF
        ) return "image/jpeg"
        if (image.size >= 8 &&
            image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte() &&
            image[2] == 'N'.code.toByte() && image[3] == 'G'.code.toByte()
        ) return "image/png"
        if (declared.startsWith("image/")) return declared
        return "image/jpeg"
    }

    // ---------------------------------------------------------------- helpers

    private fun isId3(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() &&
            b[2] == '3'.code.toByte()

    private fun indexOfZero(b: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if (b[i] == 0.toByte()) return i
            i++
        }
        return -1
    }

    private fun indexOfDoubleZero(b: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + 1 < to) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i
            i++
        }
        return -1
    }

    /** ID3 counters are "syncsafe": 7 usable bits per byte. */
    private fun syncSafeInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)
    }

    private fun beInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }

    /** Little-endian 32-bit read (RIFF chunk sizes / LIST sub-chunk sizes). */
    private fun le32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return 0L
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    /** Undo ID3v2 unsynchronisation: every 0xFF 0x00 pair collapses to a single 0xFF. */
    private fun deUnsynchronise(src: ByteArray): ByteArray {
        val out = ByteArray(src.size)
        var n = 0
        var i = 0
        while (i < src.size) {
            out[n++] = src[i]
            if (src[i] == 0xFF.toByte() && i + 1 < src.size && src[i + 1] == 0.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return if (n == src.size) out else out.copyOf(n)
    }

    private fun cacheKey(path: String, length: Long, mtime: Long): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest("$path|$length|$mtime".toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) sb.append(String.format("%02x", byte))
        return sb.toString()
    }
}

/**
 * V8.87 Private on-disk cache for covers pulled out of audio files.
 *
 * Images land in filesDir/covers and are handed out through the FileProvider that is
 * already declared in the manifest (authority "${applicationId}.provider", with a
 * files-path entry covering filesDir), so Coil can load them like any content URI.
 *
 * Only the extracted image is written - the audio file itself is never touched.
 */
object CoverArtStore {

    private const val DIR = "covers"

    private fun fileFor(context: Context, key: String): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$key.img")
    }

    /** True when a previous pass already determined this file carries no cover. */
    fun isKnownMissing(context: Context, key: String): Boolean {
        return try {
            File(context.filesDir, "$DIR/$key.none").isFile
        } catch (e: Exception) {
            false
        }
    }

    /** Remember that [key] has no embedded cover, so later scans skip the parse. */
    fun markMissing(context: Context, key: String) {
        try {
            val dir = File(context.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "$key.none").writeBytes(ByteArray(0))
        } catch (e: Exception) {
            // A failed marker only costs a re-parse; never let it break the scan.
        }
    }

    /** URI for an already-cached cover, or null when it has not been extracted yet. */
    fun uriFor(context: Context, key: String): String? {
        return try {
            val file = fileFor(context, key)
            if (!file.isFile || file.length() == 0L) return null
            FileProvider.getUriForFile(context, authority(context), file).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Persist [bytes] and return its content URI, or null on failure. */
    fun store(context: Context, key: String, bytes: ByteArray): String? {
        return try {
            // A successful extract supersedes any earlier "no cover" verdict.
            val marker = File(context.filesDir, "$DIR/$key.none")
            if (marker.isFile) marker.delete()
            val file = fileFor(context, key)
            // Write to a temp file first so a kill mid-write cannot leave a torn cover.
            val tmp = File(file.parentFile, "$key.tmp")
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            FileProvider.getUriForFile(context, authority(context), file).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Manifest declares androidx.core.content.FileProvider as "${applicationId}.provider". */
    private fun authority(context: Context): String = context.packageName + ".provider"
}
