package com.sdw.music.player.core.audio.metadata
import com.sdw.music.player.core.audio.metadata.CoverDownloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 封面下载器：iTunes Search API 优先 → MusicBrainz Cover Art Archive 兜底。
 * 缓存到 app 外部文件目录 covers/，复用 cacheName(artist, album) 作 key。
 */
object CoverDownloader {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // MusicBrainz / Cover Art Archive 专用客户端（带 User-Agent，避免 403）
    // 手机网络较慢，读超时拉到 30s，避免链路后半段（Wikidata/Wikimedia）被掐断。
    private val musicBrainzClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    // ===== 艺人头像（多源：本地文件夹 / 网易云 / QQ音乐 / Wikimedia / Last.fm）=====
    // 缓存 key 仅按歌手名；来源由设置 artist_avatar_source 决定：
    //   auto = 本地→网易云→QQ→Wikimedia→Last.fm；各源可单选。
    // 网络抓取受并发信号量(=1)+间隔延迟限制，降低被各源反爬限流的概率；
    // 「确实没有图」的歌手做负缓存，避免列表重组合时反复重试同一歌手。
    private val fetchSemaphore = Semaphore(1)
    private val failedArtists = ConcurrentHashMap.newKeySet<String>()

    // 歌手名归一化：去括号、取首个合作艺人、去 feat./ft. 后缀，提升搜索命中率。
    private fun normalizeArtistName(name: String): String {
        var s = name.trim()
        s = s.replace(Regex("\\s*[（(][^()（）]*[)）]\\s*"), " ")
        s = s.split(Regex("[/、&,]")).first().trim()
        s = s.replace(Regex("\\s*(?i)(feat\\.?|ft\\.?|featuring|vs\\.?|×)\\s.*"), "").trim()
        return s.take(60)
    }

    suspend fun downloadArtistAvatar(context: Context, artist: String): File? {
        if (artist.isBlank()) return null
        val prefs = context.getSharedPreferences("sdw_music_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("artist_avatar_enabled", true)) return null
        cachedArtistAvatar(context, artist)?.let { return it }
        if (failedArtists.contains(artist)) return null

        val source = prefs.getString("artist_avatar_source", "auto") ?: "auto"
        val key = prefs.getString("lastfm_api_key", "") ?: ""
        Log.i("ArtistAvatar", "downloadArtistAvatar artist='${artist.take(40)}' source=$source keyBlank=${key.isBlank()}")

        val local = tryLocalArtistAvatar(context, artist)
        if (source == "local") { Log.i("ArtistAvatar", "source=local -> ${if (local != null) "hit" else "miss"}"); return local }
        if (source == "auto" && local != null) { Log.i("ArtistAvatar", "auto local hit"); return local }

        var result: File? = null
        // auto 下 QQ 放网易云之前：网易云在设备侧被限流(code:405)，QQ 作为中文主力先上
        if (source == "qq" || source == "auto") result = fetchArtistAvatarQQ(context, artist)
        if (result == null && (source == "netease" || source == "auto")) result = fetchArtistAvatarNetEase(context, artist)
        if (result == null && (source == "wikimedia" || source == "auto")) result = fetchArtistAvatarWikimedia(context, artist)
        if (result == null && ((source == "lastfm" || source == "auto") && key.isNotBlank())) {
            result = fetchArtistAvatarLastFm(context, artist, key)
        }
        return result
    }

    private fun cachedArtistAvatar(context: Context, artist: String): File? {
        val dir = coverCacheDir(context)
        val hash = cacheName(artist, "")
        for (ext in listOf("jpg", "png", "webp")) {
            val f = File(dir, "artist_$hash.$ext")
            if (f.exists() && f.length() > 1024) return f
        }
        return null
    }

    private fun writeArtistAvatarCache(context: Context, artist: String, bytes: ByteArray, png: Boolean = false): File {
        val ext = if (png) "png" else "jpg"
        return File(coverCacheDir(context), "artist_${cacheName(artist, "")}.$ext").apply { writeBytes(bytes) }
    }

    // 本地文件夹：app 私有 artist_avatars/ 下按歌手名（文件名安全化）匹配图片。
    private fun tryLocalArtistAvatar(context: Context, artist: String): File? {
        val dir = File(context.getExternalFilesDir(null), "artist_avatars").also { it.mkdirs() }
        val safe = artist.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            for (name in listOf(safe, artist)) {
                val src = File(dir, "$name.$ext")
                if (src.exists() && src.length() > 1024) {
                    return writeArtistAvatarCache(context, artist, src.readBytes(), ext == "png")
                }
            }
        }
        return null
    }

    // MusicBrainz(按名查 MBID) → Wikidata(P18 图片) → Wikimedia(缩略图)。免费、无需 key。
    private suspend fun fetchArtistAvatarWikimedia(context: Context, artist: String): File? = fetchSemaphore.withPermit { try {
        val ua = USER_AGENT
        val mbUrl = "https://musicbrainz.org/ws/2/artist/?query=" +
            URLEncoder.encode("artist:\"$artist\"", "UTF-8") + "&fmt=json&limit=1"
        val mbBody = musicBrainzClient.newCall(
            Request.Builder().url(mbUrl).header("User-Agent", ua).build()
        ).execute().use { it.body?.string() ?: "" }
        val artists = JSONObject(mbBody).optJSONArray("artists") ?: run { failedArtists.add(artist); return null }
        if (artists.length() == 0) { failedArtists.add(artist); return null }
        val mbid = artists.getJSONObject(0).optString("id", "")
        if (mbid.isEmpty()) { failedArtists.add(artist); return null }
        Log.i("ArtistAvatar", "wikimedia mbid=$mbid")

        val relUrl = "https://musicbrainz.org/ws/2/artist/$mbid?fmt=json&inc=url-rels"
        val relBody = musicBrainzClient.newCall(
            Request.Builder().url(relUrl).header("User-Agent", ua).build()
        ).execute().use { it.body?.string() ?: "" }
        var qid = ""
        val rels = JSONObject(relBody).optJSONArray("relations")
        if (rels != null) {
            for (i in 0 until rels.length()) {
                val r = rels.getJSONObject(i)
                if (r.optString("type", "") == "wikidata") {
                    qid = r.optJSONObject("url")?.optString("resource", "")?.substringAfterLast('/') ?: ""
                    if (qid.isNotEmpty()) break
                }
            }
        }
        if (qid.isEmpty()) { Log.i("ArtistAvatar", "wikimedia no wikidata relation"); failedArtists.add(artist); return null }

        val wdUrl = "https://www.wikidata.org/wiki/Special:EntityData/$qid.json"
        val wdBody = musicBrainzClient.newCall(
            Request.Builder().url(wdUrl).header("User-Agent", ua).build()
        ).execute().use { it.body?.string() ?: "" }
        val claims = JSONObject(wdBody).optJSONObject("entities")
            ?.optJSONObject(qid)?.optJSONObject("claims") ?: run { failedArtists.add(artist); return null }
        val fileName = claims.optJSONArray("P18")
            ?.optJSONObject(0)?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")?.optString("value", "") ?: run { failedArtists.add(artist); return null }
        if (fileName.isEmpty()) { failedArtists.add(artist); return null }

        val fileUrl = "https://commons.wikimedia.org/wiki/Special:FilePath/" +
            URLEncoder.encode(fileName.replace(' ', '_'), "UTF-8") + "?width=500"
        Log.i("ArtistAvatar", "wikimedia fileName='$fileName'")
        val bytes = musicBrainzClient.newCall(
            Request.Builder().url(fileUrl).header("User-Agent", ua).build()
        ).execute().use { it.body?.bytes() }
        if (bytes != null && bytes.size > 1024) { val f = writeArtistAvatarCache(context, artist, bytes); Log.i("ArtistAvatar", "wikimedia saved ${f.length()}B"); f } else null
    } catch (e: Exception) { Log.w("ArtistAvatar", "wikimedia fail: ${e.message?.take(80)}"); null }
    }

    // 网易云音乐（非官方 web 搜索 API，明文 JSON，直接返回 picUrl；国内可达、中文歌手覆盖高）
    // 设备侧易被反爬限流（返回 54 字节空响应），故加浏览器头 + 间隔延迟 + 归一化重试。
    private suspend fun fetchArtistAvatarNetEase(context: Context, artist: String): File? = fetchSemaphore.withPermit { try {
        delay(300)
        var picUrl = neteaseSearchPic(artist)
        if (picUrl == null) {
            val norm = normalizeArtistName(artist)
            if (norm != artist) {
                Log.i("ArtistAvatar", "netease retry norm='$norm'")
                picUrl = neteaseSearchPic(norm)
            }
        }
        if (picUrl == null) { failedArtists.add(artist); return null }
        picUrl = picUrl.replace("http://", "https://")
        Log.i("ArtistAvatar", "netease picUrl=${picUrl.take(80)}")
        val bytes = httpClient.newCall(
            Request.Builder().url(picUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .build()
        ).execute().use { it.body?.bytes() }
        if (bytes != null && bytes.size > 1024) { val f = writeArtistAvatarCache(context, artist, bytes); Log.i("ArtistAvatar", "netease saved ${f.length()}B"); f } else null
    } catch (e: Exception) { Log.w("ArtistAvatar", "netease fail: ${e.message?.take(80)}"); null }
    }

    private fun neteaseSearchPic(query: String): String? {
        val url = "https://music.163.com/api/search/get/web?csrf_token=&s=" +
            URLEncoder.encode(query, "UTF-8") + "&type=100&limit=1"
        val body = httpClient.newCall(
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "https://music.163.com/")
                .build()
        ).execute().use { it.body?.string() ?: "" }
        Log.i("ArtistAvatar", "netease bodyLen=${body.length} q='${query.take(30)}'")
        if (body.length < 100) Log.w("ArtistAvatar", "netease empty body='${body.take(120)}'")
        val artists = JSONObject(body).optJSONObject("result")?.optJSONArray("artists") ?: return null
        if (artists.length() == 0) return null
        val p = artists.getJSONObject(0).optString("picUrl", "")
        return if (p.isEmpty()) null else p
    }

    // QQ音乐（非官方搜索 API，返回 JSONP；从 song.list[0].singer[0].mid 取歌手 mid 拼图链；
    // 中文歌手覆盖极高，作网易云之外的第二中文源，绕开其反爬限流）
    private suspend fun fetchArtistAvatarQQ(context: Context, artist: String): File? = fetchSemaphore.withPermit { try {
        delay(300)
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?aggr=1&cr=1&lostate=0&platform=yqq.json&needNewCode=1&catZhida=1&w=" +
            URLEncoder.encode(artist, "UTF-8") + "&type=100"
        val raw = httpClient.newCall(
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .build()
        ).execute().use { it.body?.string() ?: "" }
        Log.i("ArtistAvatar", "qq bodyLen=${raw.length}")
        val json = raw.substringAfter('(').substringBeforeLast(')').trim()
        val data = JSONObject(json).optJSONObject("data") ?: run { failedArtists.add(artist); return null }
        var mid = data.optJSONObject("song")?.optJSONArray("list")?.optJSONObject(0)
            ?.optJSONArray("singer")?.optJSONObject(0)?.optString("mid", "")
        if (mid.isNullOrEmpty()) {
            mid = data.optJSONObject("zhida")?.optJSONObject("zhida_singer")?.optString("singer_mid", "")
        }
        if (mid.isNullOrEmpty()) { failedArtists.add(artist); return null }
        val imgUrl = "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg"
        Log.i("ArtistAvatar", "qq mid=$mid")
        val bytes = httpClient.newCall(
            Request.Builder().url(imgUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .build()
        ).execute().use { it.body?.bytes() }
        if (bytes != null && bytes.size > 1024) { val f = writeArtistAvatarCache(context, artist, bytes); Log.i("ArtistAvatar", "qq saved ${f.length()}B"); f } else null
    } catch (e: Exception) { Log.w("ArtistAvatar", "qq fail: ${e.message?.take(80)}"); null }
    }

    // Last.fm artist.getInfo：需用户自备免费 api_key；image[] 取 mega/extralarge/large。
    private suspend fun fetchArtistAvatarLastFm(context: Context, artist: String, apiKey: String): File? = fetchSemaphore.withPermit { try {
        val url = "https://ws.audioscrobbler.com/2.0/?method=artist.getinfo" +
            "&artist=" + URLEncoder.encode(artist, "UTF-8") +
            "&api_key=" + URLEncoder.encode(apiKey, "UTF-8") + "&format=json"
        val body = httpClient.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        ).execute().use { it.body?.string() ?: "" }
        Log.i("ArtistAvatar", "lastfm bodyLen=${body.length}")
        val images = JSONObject(body).optJSONObject("artist")?.optJSONArray("image") ?: run { failedArtists.add(artist); return null }
        var imgUrl = ""
        for (size in listOf("mega", "extralarge", "large", "medium")) {
            for (i in 0 until images.length()) {
                val o = images.getJSONObject(i)
                if (o.optString("size", "") == size) {
                    imgUrl = o.optString("#text", "")
                    if (imgUrl.isNotEmpty()) break
                }
            }
            if (imgUrl.isNotEmpty()) break
        }
        if (imgUrl.isEmpty()) { failedArtists.add(artist); return null }
        Log.i("ArtistAvatar", "lastfm imgUrl=${imgUrl.take(80)}")
        val bytes = httpClient.newCall(
            Request.Builder().url(imgUrl).header("User-Agent", USER_AGENT).build()
        ).execute().use { it.body?.bytes() }
        if (bytes != null && bytes.size > 1024) { val f = writeArtistAvatarCache(context, artist, bytes); Log.i("ArtistAvatar", "lastfm saved ${f.length()}B"); f } else null
    } catch (e: Exception) { Log.w("ArtistAvatar", "lastfm fail: ${e.message?.take(80)}"); null }
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
