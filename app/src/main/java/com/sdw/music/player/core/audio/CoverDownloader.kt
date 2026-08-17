package com.sdw.music.player.core.audio

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 封面下载器：iTunes Search API 优先 → MusicBrainz Cover Art Archive 兜底。
 * 缓存到 app 外部文件目录 covers/，复用 cacheName(artist, album) 作 key。
 */
object CoverDownloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // MusicBrainz / Cover Art Archive 专用客户端（带 User-Agent，避免 403）
    private val musicBrainzClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT = "MotoMusicPremium/8.0 ( stephenywl82@gmail.com )"

    fun coverCacheDir(context: Context): File =
        File(context.getExternalFilesDir(null), "covers").also { it.mkdirs() }

    /** 下载封面，命中缓存直接返回；否则 iTunes → MusicBrainz 兜底，失败返回 null */
    fun downloadCover(context: Context, artist: String, album: String): File? {
        val hashName = cacheName(artist, album)
        val cached = File(coverCacheDir(context), "$hashName.jpg")
        if (cached.exists() && cached.length() > 1024) return cached

        val encoded = URLEncoder.encode("$artist $album", "UTF-8")
        // iTunes 优先
        try {
            val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=album&limit=5"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (body.contains("artworkUrl100")) {
                    val results = JSONObject(body).optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            var imgUrl = results.getJSONObject(i).optString("artworkUrl100", "")
                            if (imgUrl.isEmpty()) continue
                            imgUrl = imgUrl.replace("100x100bb", "600x600bb").replace("60x60bb", "600x600bb")
                            val bytes = httpClient.newCall(Request.Builder().url(imgUrl).build()).execute().use { it.body?.bytes() }
                            if (bytes != null && bytes.size > 1024) {
                                return File(coverCacheDir(context), "$hashName.jpg").apply { writeBytes(bytes) }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return downloadFromMusicBrainz(context, artist, album, hashName)
    }

    private fun downloadFromMusicBrainz(context: Context, artist: String, album: String, hashName: String): File? {
        try {
            val q = URLEncoder.encode("artist:\"$artist\" AND release:\"$album\"", "UTF-8")
            val searchUrl = "https://musicbrainz.org/ws/2/release/?query=$q&fmt=json&limit=5"
            val req = Request.Builder().url(searchUrl).header("User-Agent", USER_AGENT).build()
            val body = musicBrainzClient.newCall(req).execute().use { it.body?.string() ?: "" }
            if (!body.contains("\"id\"")) return null

            val releases = JSONObject(body).optJSONArray("releases") ?: return null
            for (i in 0 until releases.length()) {
                val mbid = releases.getJSONObject(i).optString("id", "")
                if (mbid.isEmpty()) continue
                val imgUrl = "https://coverartarchive.org/release/$mbid/front-500"
                val imgReq = Request.Builder().url(imgUrl).header("User-Agent", USER_AGENT).build()
                musicBrainzClient.newCall(imgReq).execute().use { imgResp ->
                    if (imgResp.code == 200) {
                        val bytes = imgResp.body?.bytes()
                        if (bytes != null && bytes.size > 1024) {
                            return File(coverCacheDir(context), "$hashName.jpg").apply { writeBytes(bytes) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun cacheName(artist: String, album: String): String {
        var hash = 0L
        for (c in "${artist}_${album}") hash = hash * 31 + c.code
        return java.lang.Long.toHexString(hash)
    }
}
