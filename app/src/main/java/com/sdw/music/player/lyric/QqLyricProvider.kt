package com.sdw.music.player.lyric

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐歌词提供者（V8.127）
 *
 * 唯一能提供「逐字歌词」的源：QQ 的 QRC 格式带行内逐字时间戳。
 * 流程：
 *   1. 搜索：`client_search_cp` 拿 songmid/id
 *   2. 歌词：`lyric_download.fcg?lrctype=4` 拿 QRC（hex，自定义 3DES + zlib）
 *   3. 解密 → [QrcParser] 解析 → 转增强型 LRC（`[mm:ss.xx]<mm:ss.xx>词`）
 *
 * 返回的 [LyricResult.syncedLrc] 即增强型 LRC，可被现有 LrcParser / 渲染链路直接使用，
 * 从而点亮逐字显示开关。无逐字时（部分老歌仅整行 LRC）自然退化为普通同步歌词。
 */
class QqLyricProvider : ILyricProvider {

    override val providerName: String = "Int Music"
    override val providerId: String = "QQ"

    companion object {
        private const val TAG = "QqLyricProvider"
        private const val TIMEOUT_MS = 12000L
        private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val LYRIC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val CDATA_REGEX = Regex("""<!\[CDATA\[([0-9A-Fa-f]{16,})\]\]>""")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Referer", "https://y.qq.com/portal/player.html")
                .header("User-Agent", UA)
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $url")
                return null
            }
            resp.body?.string()
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: ${e.message}")
            null
        }
    }

    /** 搜索：返回 (songMid, songId, 标题, 歌手, 时长秒) */
    private data class QqSong(val mid: String, val id: String, val title: String, val artist: String, val seconds: Int)

    private fun searchSongs(query: String, limit: Int = 10): List<QqSong> {
        val url = buildString {
            append(SEARCH_URL)
            append("?w=").append(URLEncoder.encode(query, "UTF-8"))
            append("&p=1&n=").append(limit)
            append("&format=json&cr=1&new_json=1")
        }
        val body = get(url) ?: return emptyList()
        return try {
            val list = JSONObject(body).getJSONObject("data").getJSONObject("song").getJSONArray("list")
            val out = mutableListOf<QqSong>()
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val mid = s.optString("mid")
                val id = s.optInt("id", 0).toString()
                val title = s.optString("name", s.optString("title"))
                val artist = s.optJSONArray("singer")?.let { arr ->
                    (0 until arr.length()).joinToString("/") { arr.getJSONObject(it).optString("name") }
                } ?: ""
                if (mid.isBlank() && id == "0") continue
                out.add(QqSong(mid, id, title, artist, s.optInt("interval", 0)))
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "搜索解析失败: ${e.message}")
            emptyList()
        }
    }

    /** 取 QRC（逐字）→ 增强型 LRC；失败回退整行 LRC */
    private fun fetchLyric(songId: String): String? {
        if (songId.isBlank() || songId == "0") return null
        val url = buildString {
            append(LYRIC_URL)
            append("?version=15&miniversion=82&lrctype=4")
            append("&musicid=").append(songId)
            append("&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq&g_tk=5381&nobase64=1")
        }
        val xml = get(url) ?: return null

        // 主歌词 CDATA 在最前；逐个尝试解密，取首个能解析出时间轴行的
        for (m in CDATA_REGEX.findAll(xml)) {
            val enhanced = QrcParser.hexToEnhancedLrc(m.groupValues[1])
            if (!enhanced.isNullOrBlank()) return enhanced
        }
        return null
    }

    override suspend fun search(query: String): List<LyricResult> = withContext(Dispatchers.IO) {
        val songs = searchSongs(query, 10)
        if (songs.isEmpty()) return@withContext emptyList()

        // 并发取歌词（限制超时），只保留真的解出内容的
        val results = coroutineScope {
            songs.map { s ->
                async {
                    val lrc = withTimeoutOrNull(6000L) { fetchLyric(s.id) }
                    if (lrc.isNullOrBlank()) null else LyricResult(
                        title = s.title,
                        artist = s.artist,
                        album = null,
                        syncedLrc = lrc,
                        plainLrc = null,
                        source = providerId,
                        songId = s.id
                    )
                }
            }.awaitAll().filterNotNull()
        }
        results
    }

    override suspend fun match(title: String, artist: String, duration: Long): LyricResult? = withContext(Dispatchers.IO) {
        val q = if (artist.isNotBlank()) "$title $artist" else title
        val songs = searchSongs(q, 6)
        if (songs.isEmpty()) return@withContext null

        // 优先时长（秒）最接近的
        val durSec = (duration / 1000).toInt()
        val ordered = if (durSec > 0) songs.sortedBy { kotlin.math.abs(it.seconds - durSec) } else songs

        for (s in ordered) {
            val lrc = withTimeoutOrNull(6000L) { fetchLyric(s.id) } ?: continue
            if (lrc.isBlank()) continue
            return@withContext LyricResult(
                title = s.title,
                artist = s.artist,
                album = null,
                syncedLrc = lrc,
                plainLrc = null,
                source = providerId,
                songId = s.id
            )
        }
        null
    }

    override suspend fun getLyrics(songId: String): LyricResult? = withContext(Dispatchers.IO) {
        val lrc = fetchLyric(songId) ?: return@withContext null
        LyricResult(
            title = "",
            artist = "",
            album = null,
            syncedLrc = lrc,
            plainLrc = null,
            source = providerId,
            songId = songId
        )
    }

    override suspend fun isAvailable(): Boolean = true
}
