package com.sdw.music.player.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 【V8.170】响度元数据（ReplayGain / LUFS）读取。
 *
 * 为什么自己解析而不走 MediaMetadataRetriever：
 *   平台只暴露固定的 METADATA_KEY_*，任意自定标签（REPLAYGAIN_* / iTunLOUDNESS）取不到；
 *   getExtendedMetadata() 各厂商实现不一，在这台机器上取不到 ReplayGain。
 *   而归一化用的值恰恰只藏在自定标签里，所以按容器格式直接读。
 *
 * 支持的容器：
 *   FLAC   VORBIS_COMMENT 里的 REPLAYGAIN_TRACK_GAIN / _ALBUM_GAIN / _TRACK_PEAK / _ALBUM_PEAK
 *          / REPLAYGAIN_REFERENCE_LOUDNESS
 *   MP3    ID3v2 的 TPE3 / TXXX / COMM，description 含 replaygain 即采纳
 *   M4A    ilst 下 mean="com.apple.iTunes" 的 ---- atom（replaygain_track_gain / _album_gain）
 *   OGG    Vorbis/Opus 的 comment header（与 FLAC 同构）
 *
 * 纯 Kotlin，只读不写，失败一律返回 null —— 读不到标签时归一化退化成 0 dB，不影响播放。
 */
object LoudnessTags {

    /** 读取到的响度标签。全部为 null 表示这首没有响度元数据。 */
    data class Tag(
        val trackGainDb: Float? = null,
        val albumGainDb: Float? = null,
        val trackPeak: Float? = null,
        val albumPeak: Float? = null,
        /** REPLAYGAIN_REFERENCE_LOUDNESS，单位是 dB SPL；缺省 89（ReplayGain 标准参考点）。 */
        val referenceLoudness: Float? = null,
        /** 容器直接写死的 LUFS 值（如 Apple 的 iTunLOUDNESS）；有它就不用 reference+gain 折算。 */
        val lufs: Float? = null
    ) {
        val any: Boolean get() = trackGainDb != null || albumGainDb != null || trackPeak != null
    }

    // 结果按 (path|size|mtime) 缓存：同一首歌切来切去、上下曲重复命中时避免反复 IO。
    private val cache = java.util.LinkedHashMap<String, Tag?>()
    private val cacheLock = Any()
    private const val MAX_CACHE = 256

    fun read(filePath: String): Tag? {
        val f = File(filePath)
        if (!f.isFile || f.length() < 32L) return null
        val key = "${filePath}|${f.length()}|${f.lastModified()}"
        synchronized(cacheLock) {
            cache[key]?.let { return it }
        }
        val tag = try {
            RandomAccessFile(f, "r").use { raf ->
                val ext = filePath.substringAfterLast('.').lowercase()
                when (ext) {
                    "flac" -> readFlacVorbis(raf)
                    "ogg", "opus", "oga" -> readOggComment(raf)
                    "mp3", "m4a", "mp4", "aac", "alac", "m4b", "caf" -> readTagTolerant(raf, ext)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
        synchronized(cacheLock) {
            cache[key] = tag
            if (cache.size > MAX_CACHE) {
                val it = cache.entries.iterator()
                for (i in 0 until MAX_CACHE / 4) if (it.hasNext()) it.next()
                while (it.hasNext()) it.next()
            }
        }
        return tag
    }

    // ──────────────────────────────────────────── 通用小工具

    private const val RG_TRACK = "replaygain_track_gain"
    private const val RG_ALBUM = "replaygain_album_gain"
    private const val RG_TPEAK = "replaygain_track_peak"
    private const val RG_APEAK = "replaygain_album_peak"
    private const val RG_REF = "replaygain_reference_loudness"
    private val RG_KEYS = setOf(RG_TRACK, RG_ALBUM, RG_TPEAK, RG_APEAK, RG_REF)

    /**
     * 从 "ReplayGain track gain: -6.54 dB" / "ReplayGain track peak: 0.891" 这类自由文本里
     * 抠出数值。TPE3 与部分 TXXX/COMM 都长这样，硬要求下划线形式会漏掉绝大多数真实文件。
     */
    private val RG_TEXT_RE = Regex(
        "(track|album)\\s+(gain|peak)[^\\w-]{0,8}([-+]?[0-9]*[.,][0-9]+)",
        RegexOption.IGNORE_CASE
    )

    private fun extractRgFromText(text: String, into: HashMap<String, String>) {
        RG_TEXT_RE.findAll(text).forEach { m ->
            // 分组：1 = track/album，2 = gain/peak，3 = 数值；groupValues[0] 是整个匹配
            val scope = m.groupValues[1].lowercase()
            val what = m.groupValues[2].lowercase()
            val key = when {
                scope.startsWith("track") && what.startsWith("gain") -> RG_TRACK
                scope.startsWith("album") && what.startsWith("gain") -> RG_ALBUM
                scope.startsWith("track") && what.startsWith("peak") -> RG_TPEAK
                else -> RG_APEAK
            }
            putIfAbsent(into, key, m.groupValues[3])
        }
    }

    /** "-6.54 dB" / "-6.54" / "-7,3" 这类文本统一解析成 dB 浮点数。 */
    internal fun parseDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        // 欧洲标点：逗号当小数点
        val cleaned = raw.replace(',', '.').replace('−', '-').trim()
        val idx = cleaned.indexOfAny(charArrayOf('-', '+'))
        val body = if (idx >= 0) cleaned.substring(idx) else cleaned
        val num = body.replace(Regex("[^0-9.+\\-]"), "").trim()
        if (num.isEmpty()) return null
        return num.toFloatOrNull()
    }

    private fun putIfAbsent(target: HashMap<String, String>, k: String, v: String) {
        val key = k.lowercase().replace(Regex("[^a-z0-9_]"), "")
        if (key.isNotEmpty() && !target.containsKey(key)) target[key] = v
    }

    // ──────────────────────────────────────────── FLAC / OGG：Vorbis comment

    private fun readFlacVorbis(raf: RandomAccessFile): Tag? {
        val head = ByteArray(4)
        raf.seek(0); raf.readFully(head)
        if (head[0] != 'f'.code.toByte() || head[1] != 'L'.code.toByte() ||
            head[2] != 'a'.code.toByte() || head[3] != 'C'.code.toByte()
        ) return null
        val total = raf.length()
        var offset = 4L
        var block: ByteArray? = null
        var guard = 0
        while (offset + 4L <= total && guard++ < 64) {
            raf.seek(offset)
            val hdr = ByteArray(4); raf.readFully(hdr)
            val last = (hdr[0].toInt() and 0x80) != 0
            val type = hdr[0].toInt() and 0x7F
            val len = ((hdr[1].toLong() and 0xFF) shl 16) or
                ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
            if (len < 0L || len > (16L shl 20)) return null
            val start = offset + 4L
            if (start + len > total) return null
            if (type == 4 && block == null) {
                val body = ByteArray(len.toInt()); raf.readFully(body)
                block = body
            }
            if (last) break
            offset = start + len
        }
        val b = block ?: return null
        // Vorbis comment packet: 4B vendor length + vendor + count + (len, "k=v")*N
        try {
            var p = 0
            // Vorbis comment 的三个长度字段都是小端 32-bit（与 OGG 分支一致），大端会读到垃圾。
            val vendorLen = be32Le(b, p); p += 4
            p += vendorLen.coerceIn(0, b.size)
            if (p + 4 > b.size) return null
            val count = be32Le(b, p); p += 4
            if (count < 0 || count > 4096) return null
            val map = HashMap<String, String>()
            repeat(count) {
                if (p + 4 > b.size) return@repeat
                val kvLen = be32Le(b, p); p += 4
                if (kvLen <= 0 || p + kvLen > b.size) return@repeat
                val kv = String(b, p, kvLen, StandardCharsets.UTF_8)
                p += kvLen
                val eq = kv.indexOf('=')
                if (eq > 0) putIfAbsent(map, kv.substring(0, eq), kv.substring(eq + 1))
            }
            return fromMap(map)
        } catch (e: Exception) {
            return null
        }
    }

    private fun readOggComment(raf: RandomAccessFile): Tag? {
        val total = raf.length()
        var offset = 0L
        var guard = 0
        while (offset + 27L <= total && guard++ < 32) {
            val page = ByteArray(27)
            raf.seek(offset); raf.readFully(page)
            val segments = page[26].toInt() and 0xFF
            var bodyOffset = 27
            var segSizesSum = 0
            for (i in 0 until segments) {
                if (bodyOffset + 1 > total) return null
                val s = ByteArray(1); raf.seek(offset + bodyOffset); raf.readFully(s)
                segSizesSum += s[0].toInt() and 0xFF
                bodyOffset += 1
            }
            if (bodyOffset + segSizesSum > total) return null
            val body = ByteArray(segSizesSum)
            raf.seek(offset + bodyOffset); raf.readFully(body)
            // Opus 首包是 OpusHead，注释包以 " OpusHead"/"\x01vorbis" 区分
            val isComment = body.size >= 7 && (String(body, 0, 7, StandardCharsets.US_ASCII) == " OpusHead" ||
                (body[0] == 0x03.toByte() && body[1] == 'v'.code.toByte()))
            if (isComment) {
                var p = 7 // vendor length
                if (p + 4 > body.size) return null
                val vendorLen = be32Le(body, p); p += 4
                p += vendorLen.coerceIn(0, body.size)
                if (p + 4 > body.size) return null
                val count = be32Le(body, p); p += 4
                if (count < 0 || count > 4096) return null
                val map = HashMap<String, String>()
                repeat(count) {
                    if (p + 4 > body.size) return@repeat
                    val kvLen = be32Le(body, p); p += 4
                    if (kvLen <= 0 || p + kvLen > body.size) return@repeat
                    val kv = String(body, p, kvLen, StandardCharsets.UTF_8)
                    p += kvLen
                    val eq = kv.indexOf('=')
                    if (eq > 0) putIfAbsent(map, kv.substring(0, eq), kv.substring(eq + 1))
                }
                return fromMap(map)
            }
            offset += bodyOffset + segSizesSum
        }
        return null
    }

    // ──────────────────────────────────────────── MP3: ID3v2 / APEv2(lite)

    private fun readTagTolerant(raf: RandomAccessFile, ext: String): Tag? {
        // M4A 走单独的 atom 树
        if (ext == "m4a" || ext == "mp4" || ext == "m4b" || ext == "caf" || (ext == "aac" && hasMp4Box(raf))) {
            return readMp4Ilst(raf)
        }
        return readId3(raf)
    }

    private fun hasMp4Box(raf: RandomAccessFile): Boolean {
        return try {
            raf.seek(4)
            val b = ByteArray(4); raf.readFully(b)
            b[0] == 'm'.code.toByte() && b[1] == 'o'.code.toByte() && b[2] == 'o'.code.toByte() && b[3] == 'v'.code.toByte()
        } catch (e: Exception) {
            false
        }
    }

    private fun readId3(raf: RandomAccessFile): Tag? {
        val total = raf.length()
        val magic = ByteArray(3)
        raf.seek(0); raf.readFully(magic)
        // ID3v2 的 magic 是 "ID3"。早先写成 "TAG"，等于所有带 ID3v2 的 MP3 全部漏读。
        if (magic[0] != 'I'.code.toByte() || magic[1] != 'D'.code.toByte() || magic[2] != '3'.code.toByte()) {
            return null   // 没有 ID3v2 就没有 ReplayGain（APEv2 的 header 布局版本差异太大，不猜）
        }
        val h = ByteArray(10); raf.seek(0); raf.readFully(h)
        val flags = h[5].toInt() and 0xFF
        val size = syncSafe(h[6], h[7], h[8], h[9])
        var start = 10L
        if ((flags and 0x40) != 0) {          // extended header present
            if (start + 4 > total) return null
            val ex = ByteArray(4); raf.seek(start); raf.readFully(ex)
            val xlen = syncSafe(ex[0], ex[1], ex[2], ex[3]) + 4
            start += xlen.toLong()
        }
        if (start + size > total || size <= 0) return null
        val body = ByteArray(size); raf.seek(start); raf.readFully(body)

        val map = HashMap<String, String>()
        var p = 0
        var guard = 0
        while (p + 10 <= body.size && guard++ < 2048) {
            val id = String(body, p, 4, StandardCharsets.ISO_8859_1)
            if (id.all { it.isLetterOrDigit().not() && it.isWhitespace() }) break
            val fsize = syncSafe(body[p + 4], body[p + 5], body[p + 6], body[p + 7])
            if (fsize < 0 || p + 10 + fsize > body.size) break
            val dataStart = p + 10
            val data = body.copyOfRange(dataStart, dataStart + fsize)
            val enc = if (data.isNotEmpty()) (data[0].toInt() and 0xFF) else 0
            val charset = when (enc) {
                1 -> StandardCharsets.UTF_16LE
                2 -> StandardCharsets.UTF_16BE
                3 -> StandardCharsets.UTF_8
                else -> StandardCharsets.ISO_8859_1
            }
            val text = String(data, 1.coerceAtMost(data.size), (data.size - 1).coerceAtLeast(0), charset)
            if (id == "TXXX" || id == "COMM" || id == "WXXX") {
                val nullAt = text.indexOf('\u0000')
                val desc = if (nullAt >= 0) text.substring(0, nullAt) else ""
                val value = if (nullAt >= 0) text.substring(nullAt + 1) else text
                val joined = "$desc=$value"
                val dn = desc.lowercase().replace(Regex("[^a-z0-9_]"), "")
                if (dn in RG_KEYS) putIfAbsent(map, dn, value)
                // 描述栏写的是自由文本（"ReplayGain track gain" 之类），按语义抠数值
                else if (RG_TRACK in joined.lowercase() || RG_ALBUM in joined.lowercase() ||
                    RG_TPEAK in joined.lowercase() || RG_APEAK in joined.lowercase() ||
                    RG_REF in joined.lowercase()
                ) extractRgFromText(joined, map)
            } else if (id == "TPE3") {
                // TPE3 是 ReplayGain 最常见也最脏的藏身处：
                //   "ReplayGain track gain: -6.54 dB"（带空格冒号，没有下划线）
                // 老解析器要求下划线形式，于是这条路永远是死路。这里直接按语义抠。
                extractRgFromText(text, map)
            }
            p = dataStart + fsize
        }
        return fromMap(map)
    }

    // ──────────────────────────────────────────── M4A: ilst / ---- atom

    private fun readMp4Ilst(raf: RandomAccessFile): Tag? {
        val total = raf.length()
        // 粗定位：从 0 开始线性找 "moov" atom，再在其中找 "udta"/"meta"/"ilst"。
        // 真正的完整 atom 树递归要写 200 行，这里按常见布局做定长推进即可。
        val map = HashMap<String, String>()
        var found = false
        var offset = 0L
        var guard = 0
        while (offset + 8 <= total && guard++ < 4096 && !found) {
            val hdr = ByteArray(8); raf.seek(offset); raf.readFully(hdr)
            val size = ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or
                ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
            val type = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
            val bodySize = if (size == 1L) {
                val ex = ByteArray(8); raf.seek(offset + 8); raf.readFully(ex)
                ((ex[0].toLong() and 0xFF) shl 56) or ((ex[1].toLong() and 0xFF) shl 48) or
                    ((ex[2].toLong() and 0xFF) shl 40) or ((ex[3].toLong() and 0xFF) shl 32) or
                    ((ex[4].toLong() and 0xFF) shl 24) or ((ex[5].toLong() and 0xFF) shl 16) or
                    ((ex[6].toLong() and 0xFF) shl 8) or (ex[7].toLong() and 0xFF) - 8
            } else size - 8
            val bodyStart = offset + (if (size == 1L) 16L else 8L)
            if (bodySize < 0 || bodyStart + bodySize > total) break
            if (type == "moov") {
                found = findIlst(raf, bodyStart, bodySize, map)
            }
            offset = bodyStart + bodySize
        }
        return if (map.isEmpty()) null else fromMap(map)
    }

    /** 在 range 内递归找 "ilst"，把它下面所有 ---- atom 的 mean/data 收进 map。 */
    private fun findIlst(raf: RandomAccessFile, start: Long, size: Long, map: HashMap<String, String>): Boolean {
        val total = raf.length()
        var offset = start
        val end = (start + size).coerceAtMost(total)
        var guard = 0
        var ilstAt = -1L
        var ilstSize = 0L
        while (offset + 8 <= end && guard++ < 4096) {
            val hdr = ByteArray(8); raf.seek(offset); raf.readFully(hdr)
            val sz = ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or
                ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
            val type = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
            val bodySize = if (sz == 1L) 8L else (sz - 8).coerceAtLeast(0)
            if (bodySize < 0 || offset + 8 + bodySize > total) break
            if (type == "ilst") { ilstAt = offset + 8; ilstSize = bodySize; break }
            if (type in setOf("udta", "meta", "moov")) {
                // 继续在内部找
                if (findIlst(raf, offset + 8, bodySize, map)) return true
            }
            offset += 8 + bodySize
        }
        if (ilstAt < 0) return false
        var p = ilstAt
        val stop = (ilstAt + ilstSize).coerceAtMost(total)
        var g2 = 0
        while (p + 8 <= stop && g2++ < 2048) {
            val h = ByteArray(8); raf.seek(p); raf.readFully(h)
            val sz = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            val type = String(h, 4, 4, StandardCharsets.ISO_8859_1)
            val bs = if (sz == 1L) 8L else (sz - 8).coerceAtLeast(0)
            if (sz == 0L) break
            if (p + 8 + bs > total) break
            if (type == "----") {
                readFreeAtom(raf, p + 8, bs, map)
            }
            p += 8 + bs
        }
        return true
    }

    private fun readFreeAtom(raf: RandomAccessFile, start: Long, size: Long, map: HashMap<String, String>) {
        if (size <= 0) return
        val end = (start + size).coerceAtMost(raf.length())
        var p = start
        var mean = ""
        var value = ""
        var g = 0
        while (p + 8 <= end && g++ < 16) {
            val h = ByteArray(8); raf.seek(p); raf.readFully(h)
            val sz = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            val type = String(h, 4, 4, StandardCharsets.ISO_8859_1)
            val bs = if (sz == 1L) 8L else (sz - 8).coerceAtLeast(0)
            if (bs < 0 || p + 8 + bs > end) break
            val bodyStart = p + 8
            if (type == "mean" || type == "data") {
                val raw = ByteArray(bs.toInt())
                raf.seek(bodyStart); raf.readFully(raw)
                if (type == "mean") {
                    val nulAt = raw.indexOf(0)
                    mean = String(raw, 0, if (nulAt >= 0) nulAt else raw.size, StandardCharsets.UTF_8)
                } else {
                    // data: 4B type + payload
                    val payload = if (raw.size > 4) String(raw, 4, raw.size - 4, StandardCharsets.UTF_8) else ""
                    value = payload.trim()
                }
            }
            p += 8 + bs
        }
        val full = "$mean=$value".lowercase()
        if (RG_TRACK in full || RG_ALBUM in full || RG_TPEAK in full || RG_APEAK in full || RG_REF in full) {
            val k = when {
                RG_TRACK in full -> RG_TRACK
                RG_ALBUM in full -> RG_ALBUM
                RG_TPEAK in full -> RG_TPEAK
                RG_APEAK in full -> RG_APEAK
                else -> RG_REF
            }
            putIfAbsent(map, k, value)
        }
    }

    // ──────────────────────────────────────────── 汇聚

    private fun fromMap(map: HashMap<String, String>): Tag {
        val trackGain = parseDb(map[RG_TRACK])
        val albumGain = parseDb(map[RG_ALBUM])
        val trackPeak = parseDb(map[RG_TPEAK])
        val albumPeak = parseDb(map[RG_APEAK])
        // 有些编码器把 ref 写成 "-23" 之类的 LUFS 参考，这里兼容两种语义：
        // 标准写法是 89（dB SPL）。非 89 的值按 LUFS 参考处理。
        val rawRef = parseDb(map[RG_REF])
        val reference = when {
            rawRef == null -> null
            rawRef >= 70f -> rawRef            // 89 dB SPL 标准参考点
            else -> null                        // 非标准的 LUFS 参考，交给 lufs 字段
        }
        val lufs = if (rawRef != null && rawRef < 70f) rawRef else null
        return Tag(
            trackGainDb = trackGain,
            albumGainDb = albumGain,
            trackPeak = trackPeak,
            albumPeak = albumPeak,
            referenceLoudness = reference,
            lufs = lufs
        )
    }

    // ──────────────────────────────────────────── 字节序工具

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun beIntLe(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun be32Le(b: ByteArray, off: Int): Int = beIntLe(b, off)

    private fun syncSafe(a: Byte, b: Byte, c: Byte, d: Byte): Int =
        ((a.toInt() and 0x7F) shl 21) or ((b.toInt() and 0x7F) shl 14) or
            ((c.toInt() and 0x7F) shl 7) or (d.toInt() and 0x7F)
}
