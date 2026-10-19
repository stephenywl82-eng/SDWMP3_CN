package com.sdw.music.player
import com.sdw.music.player.core.audio.analysis.AudioAnalyzer
import com.sdw.music.player.core.audio.metadata.FlacCoverReader
import com.sdw.music.player.core.audio.metadata.LyricsEmbedder
import com.sdw.music.player.core.audio.analysis.MediaProbe
import com.sdw.music.player.core.audio.playback.PlayerConnection
import com.sdw.music.player.core.audio.metadata.WavCoverReader

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.sdw.music.player.core.audio.metadata.CoverArtStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Folders数据模型
 */
data class Folder(
    val path: String,
    val name: String,
    val songCount: Int,
    val songs: List<Song> = emptyList()
)


/**
 * SongRepository: 歌曲仓库，支持媒体扫描 / Folders查询 / BPM-Key 解析
 */
object SongRepository {
    private const val TAG = "SongRepository"
    @Volatile private var songs: List<Song> = emptyList()
    @Volatile
    var dataVersion: Long = 0
        private set

    /** Folders列表版本号，每次Folders内容变化 +1，供 UI 观察 */
    private val _foldersVersion = MutableStateFlow(0L)
    val foldersVersion: StateFlow<Long> = _foldersVersion.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    /**
     * 「修复媒体库」专用进度。
     *
     * 刻意与全库扫描共用的 _scanProgress 分开：两者跑在同一条数据链路上，
     * 共用一个流会互相覆盖（repair 跑到一半被列表扫描的进度顶掉，UI 忽快忽慢）。
     * null = 未在跑；跑完复位回 null，UI 自动回到按钮态。
     */
    data class RepairProgress(val phase: Int, val done: Int, val total: Int)

    private val _repairProgress = MutableStateFlow<RepairProgress?>(null)
    val repairProgress: StateFlow<RepairProgress?> = _repairProgress.asStateFlow()

    private var appCtx: Context? = null
    // [V8.214] 缓存恢复移出主线程：原实现在 SDWApp.onCreate / ViewModel.init 主线程同步解析
    // song_cache.json（大库 2479 首 JSON 解析 + 对象构建）→ 冷启动主线程阻塞数秒 → 系统弹「没有响应」。
    // 改为后台协程加载，完成后通过 songsFlow 通知 UI（FAST PATH 仍生效，仅晚数十~数百 ms）。
    private val loadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _songsFlow = MutableStateFlow<List<Song>>(emptyList())
    val songsFlow: StateFlow<List<Song>> = _songsFlow.asStateFlow()
    @Volatile private var cacheLoadStarted = false
    private var lastSavedHash: Int = 0  // debounce saveSongsToDiskCache


    fun init(context: Context) {
        appCtx = context.applicationContext
        PlaylistManager.init(context)   // 自身幂等
        // [V8.214] 磁盘缓存**只触发一次**，且放到后台线程解析，避免阻塞主线程导致开屏 ANR。
        if (cacheLoadStarted) return
        cacheLoadStarted = true
        loadScope.launch { loadSongsFromDiskCache() }
    }

    /**
     * 从磁盘缓存恢复歌曲列表（同步调用，应在主线程尽快Back）
     * 【V7.50】改用文件存储替代 SharedPreferences（避免大数据 commit 失败）
     * 【V7.91】增强日志，记录文件路径和大小，方便定位问题
     */
    private fun loadSongsFromDiskCache() {
        val ctx = appCtx ?: return
        try {
            val cacheFile = java.io.File(ctx.filesDir, "song_cache.json")
            android.util.Log.d(TAG, "loadSongsFromDiskCache: path=${cacheFile.absolutePath}, exists=${cacheFile.exists()}, size=${if (cacheFile.exists()) cacheFile.length() else 0}")
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                android.util.Log.d(TAG, "loadSongsFromDiskCache: cache file missing or empty")
                return
            }
            val json = cacheFile.readText(Charsets.UTF_8)
            if (json.isBlank()) {
                android.util.Log.w(TAG, "loadSongsFromDiskCache: file content is blank")
                return
            }
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<Song>()
            // [V8.93] 旧版本已经把 "<unknown>" 写进过缓存：解析时顺带清洗（由
            // parseSongFromJson -> sanitizeArtist/Album 完成），并在下面回写一次，
            // 避免每次启动都重洗一遍。
            var needsRewrite = false
            // [V8.105] 缓存恢复同样要过一遍扫描过滤。
            //   背景：V8.101 曾把「读不出时长」的行放行，短音频 / 录音因此被写进缓存；
            //   而启动 FAST PATH 是直接吃缓存、不重扫的，这些行会一直显示到用户手动
            //   「修复媒体库」为止 —— 这就是「点了修复还是什么都扫出来」的残余来源。
            //   这里用与扫描侧同一套判据（路径 + 时长）净化，命中即丢弃并回写缓存。
            val minSecCache = minDurationSecNow(ctx)
            var probeBudget = 60   // 主线程最多现读 60 次，避免极端情况下拖慢启动
                // [V8.112b] 视频探测迁移开关：旧缓存（无标记）第一次仍全量验一遍并回写，
                // 之后启动直接跳过 → 冷启动从 ~11s 降到 ~1s。
                val videoProbeMigrated = ctx.getSharedPreferences("sdw_music_prefs", Context.MODE_PRIVATE)
                    .getBoolean("cache_video_verified", false)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (isPlaceholderArtist(obj.optString("artist", "")) ||
                    isPlaceholderAlbum(obj.optString("album", ""))
                ) needsRewrite = true
                val s = parseSongFromJson(obj) ?: continue
                val fp = if (s.filePath.isNotBlank()) s.filePath else s.path
                // 路径 / 铃声名黑名单：纯字符串判据，content:// 行同样安全
                if (isExcludedByPathRules(fp)) { needsRewrite = true; continue }
                // [V8.106] 缓存恢复也排视频：历史上修复兜底把 .mp4 视频写进过缓存，
                // 不在这里清掉的话每次冷启动都会从缓存把视频救回来。
                // 只对本地路径行验（content:// 行来自 MediaStore Audio 表，不可能是视频）。
                // [V8.112b] 恢复期**不再逐行**开 MediaExtractor 验流：2479 行里 124 行落在
                // VIDEO_CAPABLE_EXTS，每行 ~80ms ≈ 10 秒主线程黑屏（时长探测有 60 次预算，
                // 视频探测没有）。缓存里的行都是扫描阶段已验过的，直接信任。
                // 迁移：仅当缓存来自旧版本（无 "novideo" 标记）时，第一次全量清一遍并回写标记。
                if (fp.startsWith("/") && !videoProbeMigrated && containsVideoStream(fp)) { needsRewrite = true; continue }
                // 时长：本地文件行若时长未知，现读一次（限量）；读不出按「太短」排除。
                // content:// 行拿不到本地路径，无法判时长 → -1 表示「未知，保留」。
                val isLocal = fp.startsWith("/")
                val durMs = when {
                    s.duration > 0 -> s.duration
                    isLocal && probeBudget > 0 -> { probeBudget--; probeDurationMs(fp) }
                    else -> -1L
                }
                if (durMs >= 0 && isTooShort(durMs, minSecCache)) { needsRewrite = true; continue }
                list.add(s)
            }
            if (list.isNotEmpty()) {
                songs = list
                dataVersion++
                _songsFlow.value = list   // [V8.214] 通知 UI 缓存已就绪（FAST PATH 刷新）
                // [V8.112b] 记下「本缓存已做完整视频验流」，后续启动走快路径。
                if (!videoProbeMigrated) {
                    ctx.getSharedPreferences("sdw_music_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("cache_video_verified", true).apply()
                }
                if (needsRewrite) {
                    saveSongsToDiskCache(list)
                    android.util.Log.i(TAG, "loadSongsFromDiskCache: sanitized placeholder artist/album, cache rewritten")
                }
                android.util.Log.i(TAG, "loadSongsFromDiskCache: ✓ restored ${list.size} songs from cache (${cacheFile.length()} bytes)")
            } else {
                android.util.Log.w(TAG, "loadSongsFromDiskCache: JSON parsed but list is empty")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "loadSongsFromDiskCache failed: ${e.message}", e)
        }
    }

    /**
     * 将歌曲列表写入磁盘缓存（JSON 文件，避免 SharedPreferences 大小限制）
     * 【V7.50】改用文件存储，同步写入确保持久化
     * 【V7.91】原子写入：先写临时文件再 rename，防止写入中途进程被杀导致缓存损坏
     */
    private fun saveSongsToDiskCache(songList: List<Song>): Unit = synchronized(this) {
        val ctx = appCtx ?: return
        try {
            // Debounce: skip if song list hasn't changed since last save
            val newHash = songList.hashCode()
            if (newHash == lastSavedHash) return
            val arr = org.json.JSONArray()
            for (s in songList) {
                arr.put(songToJson(s))
            }
            val jsonStr = arr.toString()
            val cacheFile = java.io.File(ctx.filesDir, "song_cache.json")
            val tempFile = java.io.File(ctx.filesDir, "song_cache.json.tmp")
            // 原子写入：先写临时文件，成功后 rename 覆盖
            tempFile.writeText(jsonStr, Charsets.UTF_8)
            if (tempFile.exists() && tempFile.length() > 0) {
                if (cacheFile.exists()) cacheFile.delete()
                val renamed = tempFile.renameTo(cacheFile)
                if (!renamed) {
                    // rename 失败（跨文件系统等罕见情况），回退到直接写入
                    cacheFile.writeText(jsonStr, Charsets.UTF_8)
                    tempFile.delete()
                }
            } else {
                android.util.Log.e(TAG, "saveSongsToDiskCache: temp file write failed, aborting")
                tempFile.delete()
                return
            }
            android.util.Log.i(TAG, "saveSongsToDiskCache: ✓ saved ${songList.size} songs (${cacheFile.length()} bytes)")
            lastSavedHash = newHash
        } catch (e: Exception) {
            android.util.Log.e(TAG, "saveSongsToDiskCache failed: ${e.message}", e)
        }
    }

    private fun songToJson(s: Song): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("artist", s.artist)
        put("album", s.album)
        put("duration", s.duration)
        put("path", s.path)
        put("albumArtUri", s.albumArtUri)
        put("format", s.format)
        put("filePath", s.filePath)
        put("folderPath", s.folderPath)
        put("bpm", s.bpm)
        put("key", s.key)
        put("dateAdded", s.dateAdded)
        put("isFavorite", s.isFavorite)
        put("lastPlayedAt", s.lastPlayedAt)
    }

    private fun parseSongFromJson(obj: org.json.JSONObject): Song? {
        return try {
            Song(
                id = obj.getLong("id"),
                title = obj.optString("title", "Unknown"),
                artist = sanitizeArtist(obj.optString("artist", "")),
                album = sanitizeAlbum(obj.optString("album", "")),
                duration = obj.optLong("duration", 0),
                path = obj.optString("path", ""),
                albumArtUri = obj.optString("albumArtUri", ""),
                format = obj.optString("format", "AUDIO"),
                filePath = obj.optString("filePath", ""),
                folderPath = obj.optString("folderPath", ""),
                bpm = obj.optInt("bpm", 0),
                key = obj.optString("key", ""),
                dateAdded = obj.optLong("dateAdded", 0),
                isFavorite = obj.optBoolean("isFavorite", false),
                lastPlayedAt = obj.optLong("lastPlayedAt", 0L)
            )
        } catch (_: Exception) { null }
    }

    // [V8.90 / V8.91] 保留已经 heal 好的封面 URI 与 WAV 文本标签。
    //
    // WAV 的封面与标签是启动后异步补的（healWavMetadata）；但 currentSong /
    // servicePlaylist / 磁盘缓存随后可能被一份 **heal 之前的旧快照** 整体覆盖。典型路径：
    // PlayerViewModel.init -> syncRestoreOrAutoPlay -> PlayerConnection.setSongs
    //   -> SongRepository.setSongs，其落盘在 scope.launch 里异步执行，晚于 heal。
    // 旧快照里 WAV 的 albumArtUri 仍是 MediaStore 的 albumart 虚拟表值、标题还是文件名、
    // 歌手还是 <unknown>，整体替换会把 heal 的成果冲掉 —— 结果：磁盘缓存永远停在未 heal，
    // 且每次启动都重跑一遍 heal（日志重复出现 "filled 7 WAV cover(s)"）。
    //
    // 这里按 song.id 做一次「保留」，判据与 healWavMetadata 的守卫完全一致，
    // 不依赖调用时序，也不改变列表顺序。文本字段只在 WAV 上判，避免误伤其它格式。
    private fun preserveHealedMetadata(incoming: List<Song>): List<Song> {
        if (songs.isEmpty() || incoming.isEmpty()) return incoming
        val prev = songs.associateBy { it.id }
        return incoming.map { s ->
            val old = prev[s.id]
            if (old == null) {
                s
            } else {
                var out = s
                // 封面：旧值是真 URI（不是 albumart 虚拟表）而新值是虚拟表 / 空 → 保留旧值
                val oldArt = old.albumArtUri
                val oldArtIsReal = oldArt.isNotBlank() &&
                    !oldArt.contains("/audio/albumart", ignoreCase = true)
                val newArtIsVirtual = s.albumArtUri.isBlank() ||
                    s.albumArtUri.contains("/audio/albumart", ignoreCase = true)
                if (oldArtIsReal && newArtIsVirtual) out = out.copy(albumArtUri = oldArt)
                // 文本：仅 WAV，且新值明显退化成文件名 / 文件夹名 / Unknown 时才保留旧值
                if (s.format.equals("WAV", true)) {
                    if (looksLikeFileName(s.title, s.filePath) && old.title.isNotBlank()) {
                        out = out.copy(title = old.title)
                    }
                    if (looksLikeArtistFallback(s.artist, s.filePath) && old.artist.isNotBlank()) {
                        out = out.copy(artist = old.artist)
                    }
                    if (looksLikeAlbumFallback(s.album, s.filePath) && old.album.isNotBlank()) {
                        out = out.copy(album = old.album)
                    }
                }
                out
            }
        }
    }

    // ---- [V8.91] 「这个值是不是退化占位」判据，扫描期与 heal 共用同一套 ----

    private fun fileNameWithoutExt(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.')

    private fun folderNameOf(path: String): String =
        path.substringBeforeLast('/', "").substringAfterLast('/')

    /**
     * Collapse a string for comparison: keep only letters/digits/CJK and lowercase.
     *
     * Needed because MediaStore和文件名并不逐字相同：文件名里的下划线到了媒体库里常变成
     * 空格（"湘女多情_mix_by…" vs "湘女多情 mix by…"），直接字符串相等会漏判。
     */
    private fun normalizeForCompare(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** MediaStore 对没有标题标签的文件直接给文件名（下划线/空格差异不算差异）。 */
    private fun looksLikeFileName(value: String, path: String): Boolean {
        if (value.isBlank()) return true
        val base = fileNameWithoutExt(path)
        if (base.isBlank()) return false
        val a = normalizeForCompare(value)
        val b = normalizeForCompare(base)
        return a.isNotEmpty() && a == b
    }

    /** 没有艺术家标签时，媒体库给 <unknown> / Unknown Artist，或退化成所在文件夹名。 */
    private fun looksLikeArtistFallback(value: String, path: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return true
        if (v.equals("Unknown Artist", true) || v.equals("Unknown", true)) return true
        if (v == "<unknown>" || v == "未知艺术家" || v == "未知") return true
        val folder = folderNameOf(path)
        return folder.isNotBlank() && v == folder
    }

    /** 没有专辑标签时，媒体库给 Unknown Album，或退化成所在文件夹名。 */
    private fun looksLikeAlbumFallback(value: String, path: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return true
        if (v.equals("Unknown Album", true) || v.equals("Unknown", true)) return true
        if (v == "<unknown>" || v == "未知专辑") return true
        val folder = folderNameOf(path)
        return folder.isNotBlank() && v == folder
    }

    // ── [V8.93] 封面/元数据判据区 ─────────────────────────────────────
    //
    // MediaStore 对没有标签的文件返回的是**字面量** "<unknown>"（不是 null）。旧代码只做
    // null 兜底，于是 "<unknown>" 一路穿到落盘缓存、歌手分组、通知栏和歌词搜索。
    // 这里在扫描 / 缓存解析入口统一收敛成 ""，显示层再用 stringResource 决定兜底文案。
    private val PLACEHOLDER_ARTISTS = setOf(
        "<unknown>", "unknown artist", "unknown", "未知歌手", "未知艺术家", "未知"
    )
    private val PLACEHOLDER_ALBUMS = setOf(
        "<unknown>", "unknown album", "unknown", "未知专辑", "未知"
    )

    fun isPlaceholderArtist(value: String?): Boolean =
        value != null && PLACEHOLDER_ARTISTS.contains(value.trim().lowercase())

    fun isPlaceholderAlbum(value: String?): Boolean =
        value != null && PLACEHOLDER_ALBUMS.contains(value.trim().lowercase())

    /** 归一化歌手：空白 / 占位字面量 -> "" */
    fun sanitizeArtist(value: String?): String =
        if (value.isNullOrBlank() || isPlaceholderArtist(value)) "" else value.trim()

    /** 归一化专辑：空白 / 占位字面量 -> "" */
    fun sanitizeAlbum(value: String?): String =
        if (value.isNullOrBlank() || isPlaceholderAlbum(value)) "" else value.trim()

    /**
     * 真封面判据（V8.94 修正）。
     *
     * **不能**把 /audio/albumart 整条路径当死链：content://media/external/audio/
     * albumart/<albumId> 正是 MP3/FLAC 从内嵌标签取封面的唯一正常途径。只有专辑未知时
     * 扫描器才会写成字面的 ".../albumart/null"，那才是真正取不到图的死链。
     *
     * V8.93 把两者一刀切，导致所有带封面的 MP3/FLAC 被判成「无封面」，各界面只剩 logo。
     *
     * 对于「有 albumId 但实际无图」的行（WAV、未嵌图的专辑），上层 Image 解码失败后会
     * 透明，自然露出底层 DefaultCoverImage 的 logo —— 这正是 V8.93 布局的设计意图，
     * 所以这里不做 I/O 试探。
     */
    fun hasRealCover(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val u = uri.lowercase()
        // 字面死链：专辑未知时的 albumart/null
        if (u.endsWith("/albumart/null") || u.endsWith("/albumart/")) return false
        return true
    }

    fun setSongs(songList: List<Song>) {
        // [V8.90] 不再无条件整体替换，避免冲掉 heal 补好的封面 URI 与 WAV 文本标签
        val merged = preserveHealedMetadata(songList)
        songs = merged
        dataVersion++
        saveSongsToDiskCache(merged)
    }

    /**
     * V8.138 Called right after a rewrite of an audio file (embedded lyrics / cover edit).
     *
     * A rewrite changes the file's size and mtime, which is exactly what the platform keys its
     * albumart row on; nothing tells the media library, so the row goes stale, the platform
     * stops serving a picture and every screen silently falls back to the logo - the user sees
     * "保存内嵌歌词把我的封面删了". The picture is in fact still inside the file.
     *
     * Two-pronged, both best-effort:
     *   ① ask MediaScannerConnection to re-examine the file so the platform row is rebuilt;
     *   ② pull the picture straight out of the container into our own FileProvider cache and
     *      return that URI, so showing the cover never depends on the platform index.
     *
     * Returns the fresh albumArtUri for the song at [filePath], or null when the song is not in
     * the library / the file carries no readable picture (caller then keeps the old value).
     */
    suspend fun refreshArtAfterRewrite(context: Context, filePath: String): String? =
        withContext(Dispatchers.IO) {
            if (filePath.isBlank() || !filePath.startsWith("/")) return@withContext null
            // ① let the media library rebuild this row from the new size / mtime.
            try {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "refreshArtAfterRewrite: scanFile failed: " + e.message)
            }
        // ② re-extract the embedded picture into our own cache.
        val ext = filePath.substringAfterLast('.', "").lowercase()
        val uri = try {
            when {
                ext == "flac" || ext == "fla" ->
                    FlacCoverReader.coverUriForFile(context, filePath)
                        ?: extractEmbeddedPicture(context, filePath)
                ext == "wav" || ext == "wave" ->
                    WavCoverReader.coverUriForFile(context, filePath)
                        ?: extractEmbeddedPicture(context, filePath)
                // 【封面回刷】MP3 / M4A / OGG / OPUS / AAC 等：用 MediaMetadataRetriever 抽出内嵌
                // APIC 帧，写进 CoverArtStore（key 含 size+mtime → 每次文件被改写都得到新 URI），
                // 这样 Coil 不会因 albumart URI 字符串不变而一直喂旧图。
                else -> extractEmbeddedPicture(context, filePath)
            }
        } catch (e: Exception) {
            null
        }
            // ③ write it back into the repository (editing does not change the song id).
            // uri 为 null 表示文件已无内嵌封面（被删除）：照样清掉仓库里残留的旧 URI 并持久化，
            // 否则下次启动/重扫又会从旧缓存把老封面顶回来。
            val idx = songs.indexOfFirst { it.filePath == filePath }
            if (idx >= 0) {
                // uri 为 null 表示封面已被删除：用空串清掉仓库残留（与扫描器"无封面=空 albumArtUri"约定一致），
                // 否则下次启动/重扫又会从旧缓存把老封面顶回来。
                val stored = uri ?: ""
                if (songs[idx].albumArtUri != stored) {
                    songs = songs.map { if (it.filePath == filePath) it.copy(albumArtUri = stored) else it }
                    dataVersion++
                    _foldersVersion.value++
                    saveSongsToDiskCache(songs)
                    android.util.Log.i(TAG, "refreshArtAfterRewrite: cover ${if (uri == null) "cleared (no embedded picture)" else "refreshed from container"}")
                }
            }
            uri
        }

    /**
     * 抽出任意可识别音频文件的内嵌封面（MP3 / M4A / OGG / OPUS / AAC 等），写进 CoverArtStore
     * 缓存并返回其内容 URI；无封面时返回 null。key 含 path+size+mtime，所以文件被 Lyrico 改写后
     * 必然 miss 重抽、得到新 URI —— 这是让播放器即时显示新封面的关键（平台 albumart URI 改写后
     * 字符串不变，Coil 会一直喂旧图）。
     */
    private fun extractEmbeddedPicture(context: Context, filePath: String): String? {
        val file = java.io.File(filePath)
        if (!file.isFile || file.length() == 0L) return null
        val key = coverCacheKey(filePath, file.length(), file.lastModified())
        CoverArtStore.uriFor(context, key)?.let { return it }
        if (CoverArtStore.isKnownMissing(context, key)) return null
        val bytes = try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(filePath)
            val pic = mmr.embeddedPicture
            mmr.release()
            pic
        } catch (e: Exception) {
            android.util.Log.w(TAG, "extractEmbeddedPicture: MediaMetadataRetriever failed: " + e.message)
            null
        }
        if (bytes == null) {
            CoverArtStore.markMissing(context, key)
            return null
        }
        return CoverArtStore.store(context, key, bytes)
    }

    private fun coverCacheKey(path: String, length: Long, mtime: Long): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest("$path|$length|$mtime".toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun getSongs(): List<Song> = songs

    fun hasData(): Boolean = songs.isNotEmpty()

    fun getSong(index: Int): Song? {
        return if (index in songs.indices) songs[index] else null
    }

    fun size(): Int = songs.size

    fun removeSongById(id: Long) {
        songs = songs.filter { it.id != id }
        dataVersion++
        _foldersVersion.value++
        saveSongsToDiskCache(songs)
    }

    fun updateSongTitle(id: Long, newTitle: String) {
        songs = songs.map { if (it.id == id) it.copy(title = newTitle) else it }
        dataVersion++
        saveSongsToDiskCache(songs)
    }

    fun updateSongTitleAndArtist(id: Long, newTitle: String, newArtist: String) {
        songs = songs.map { if (it.id == id) it.copy(title = newTitle, artist = newArtist) else it }
        dataVersion++
        saveSongsToDiskCache(songs)
    }

    /** 记录歌曲播放时间，并持久化到磁盘缓存 */
    fun recordPlayed(id: Long) {
        val now = System.currentTimeMillis() / 1000
        songs = songs.map { if (it.id == id) it.copy(lastPlayedAt = now) else it }
        dataVersion++
        saveSongsToDiskCache(songs)
    }

    /** 持久化当前歌曲数据到磁盘（退出时调用） */
    fun persistNow() {
        saveSongsToDiskCache(songs)
    }

    // ==================== Folders管理 ====================

    fun getFolders(): List<Folder> {
        if (songs.isEmpty()) {
            val cached = loadFoldersFromCache()
            if (cached.isNotEmpty()) {
                android.util.Log.d(TAG, "getFolders: restored ${cached.size} folders from disk cache")
                return cached
            }
            return emptyList()
        }
        return buildFoldersFromSongs(songs)
    }

    private fun loadFoldersFromCache(): List<Folder> {
        val ctx = appCtx ?: return emptyList()
        val prefs = ctx.getSharedPreferences("folder_list_cache", Context.MODE_PRIVATE)
        val json = prefs.getString("folders_json", "") ?: ""
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val result = mutableListOf<Folder>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(Folder(
                    path = obj.getString("path"),
                    name = obj.getString("name"),
                    songCount = obj.getInt("songCount"),
                    songs = emptyList()
                ))
            }
            result
        } catch (_: Exception) { emptyList() }
    }

    fun saveFoldersToCache(folders: List<Folder>) {
        val ctx = appCtx ?: return
        val prefs = ctx.getSharedPreferences("folder_list_cache", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray()
        for (f in folders) {
            val obj = org.json.JSONObject()
            obj.put("path", f.path)
            obj.put("name", f.name)
            obj.put("songCount", f.songCount)
            try {
                val firstSong = f.songs.firstOrNull()
                obj.put("albumArtUri", firstSong?.albumArtUri ?: "")
            } catch (_: Exception) {
                obj.put("albumArtUri", "")
            }
            arr.put(obj)
        }
        prefs.edit().putString("folders_json", arr.toString()).apply()
        android.util.Log.d(TAG, "saveFoldersToCache: saved ${folders.size} folders")
    }

    private fun buildFoldersFromSongs(songList: List<Song>): List<Folder> {
        val folderMap = mutableMapOf<String, MutableList<Song>>()
        for (song in songList) {
            val fp = song.filePath.ifBlank { song.path }
            val parentPath = fp.substringBeforeLast("/", "")
            if (parentPath.isNotEmpty()) {
                folderMap.getOrPut(parentPath) { mutableListOf() }.add(song)
            }
        }
        return folderMap.map { (path, songsInFolder) ->
            Folder(
                path = path,
                name = path.substringAfterLast("/"),
                songCount = songsInFolder.size,
                songs = songsInFolder
            )
        }.sortedByDescending { it.songCount }
    }

    // ==================== Search ====================

    fun searchSongs(query: String): List<Song> {
        if (query.isBlank()) return songs
        val lowerQuery = query.lowercase().trim()
        return songs.filter { song ->
            song.title.lowercase().contains(lowerQuery) ||
            song.artist.lowercase().contains(lowerQuery) ||
            song.album.lowercase().contains(lowerQuery)
        }
    }

    fun getSongsInFolder(folderPath: String): List<Song> {
        val normalizedFolderPath = folderPath.trimEnd('/')
        return songs.filter { song ->
            val fp = song.filePath.ifBlank { song.path }
            fp.startsWith(normalizedFolderPath) &&
                (fp.length == normalizedFolderPath.length || fp[normalizedFolderPath.length] == '/')
        }
    }

    // === 目录树导航（多级文件夹浏览） ===

    // 存储卷根：主存储 /storage/emulated/0 + SD 卡 /storage/XXXX-XXXX
    private val VOLUME_ROOT_REGEX = Regex("^(/storage/(?:emulated/0|[0-9A-F]{4}-[0-9A-F]{4}))")

    /** 返回所有存储卷根（主存储 + SD 卡），供根视图列出顶层目录 */
    fun getStorageVolumeRoots(): List<String> {
        val roots = mutableSetOf<String>()
        for (song in songs) {
            val fp = song.filePath.ifBlank { song.path }
            VOLUME_ROOT_REGEX.find(fp)?.groupValues?.get(1)?.let { roots.add(it) }
        }
        if (roots.isEmpty()) {
            roots.add(android.os.Environment.getExternalStorageDirectory().absolutePath)
        }
        return roots.toList().sorted()
    }

    /** 返回某目录的直接子目录（仅含歌的），返回完整路径，按名称排序 */
    fun getSubfoldersOf(parentPath: String): List<String> {
        val normalized = parentPath.trimEnd('/')
        val result = mutableSetOf<String>()
        for (song in songs) {
            val fp = song.filePath.ifBlank { song.path }
            if (!fp.startsWith("$normalized/")) continue
            val rest = fp.removePrefix("$normalized/")
            val slashIdx = rest.indexOf('/')
            if (slashIdx > 0) {
                result.add("$normalized/${rest.substring(0, slashIdx)}")
            }
        }
        return result.toList().sortedBy { it.substringAfterLast('/').lowercase() }
    }

    /** 返回某目录下「直接」的歌（不含子目录） */
    fun getDirectSongsIn(folderPath: String): List<Song> {
        val normalized = folderPath.trimEnd('/')
        return songs.filter { song ->
            val fp = song.filePath.ifBlank { song.path }
            fp.substringBeforeLast('/', "") == normalized
        }
    }

    fun getSongById(id: Long): Song? = songs.find { it.id == id }

    /**
     * 【v8.13】从 BpmKeyCache 补 BPM/Key 到内存 songs（批量扫描后调用）。
     * 只补 bpm<=0 的歌，返回更新数。
     */
    fun applyBpmCache(context: Context): Int {
        var updated = 0
        songs = songs.map { s ->
            if (s.bpm > 0) s
            else {
                val path = s.filePath.ifEmpty { s.path }
                if (path.isBlank() || path.startsWith("content://")) s
                else {
                    val cached = BpmKeyCache.get(path)
                    if (cached != null && cached.first > 0) {
                        updated++
                        s.copy(bpm = cached.first, key = if (s.key.isBlank()) cached.second else s.key)
                    } else s
                }
            }
        }
        if (updated > 0) android.util.Log.d(TAG, "applyBpmCache: updated $updated songs")
        return updated
    }

    // ==================== BPM / Key 解析 ====================

    fun readBpmAndKey(filePath: String): Pair<Int, String> {
        return try {
            when {
                filePath.endsWith(".flac", true) -> readFlacVorbisComments(filePath)
                filePath.endsWith(".mp3", true) -> readId3v2Tags(filePath)
                filePath.endsWith(".ogg", true) -> readFlacVorbisComments(filePath)
                else -> Pair(0, "")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "readBpmAndKey failed for $filePath: ${e.message}")
            Pair(0, "")
        }
    }

    fun readFlacVorbisComments(filePath: String): Pair<Int, String> {
        val raf = RandomAccessFile(filePath, "r")
        try {
            val magic = ByteArray(4)
            raf.read(magic)
            if (String(magic) != "fLaC") return Pair(0, "")

            var bpm = 0
            var key = ""

            var blocksRead = 0
            while (true) {
                if (++blocksRead > 64) break
                val header = ByteArray(4)
                if (raf.read(header) < 4) break

                val blockType = header[0].toInt() and 0x7F
                val isLast = (header[0].toInt() and 0x80) != 0

                val dataLength = ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)

                // 【V9.19】退化护栏：损坏/全零填充的 FLAC 会出现「blockType=0 且 dataLength=0 且非末块」，
                // 导致 skipBytes(0) 后无限读下一段零头 → 解析死循环、扫描永卡在这一首。
                // 真·FLAC 必有 STREAMINFO（dataLength=34）且末块置 isLast，不会命中此分支。
                if (blockType == 0 && dataLength == 0 && !isLast) break

                if (blockType == 4) {
                    val data = ByteArray(dataLength)
                    raf.read(data)
                    val comments = parseVorbisComments(data)

                    comments["BPM"]?.toIntOrNull()?.let { if (it in 20..300) bpm = it }
                    if (bpm == 0) comments["bpm"]?.toIntOrNull()?.let { if (it in 20..300) bpm = it }
                    if (bpm == 0) comments["TEMPO"]?.toIntOrNull()?.let { if (it in 20..300) bpm = it }

                    key = comments["INITIALKEY"] ?: comments["initialkey"] ?:
                          comments["KEY"] ?: comments["key"] ?: ""
                    if (key.length > 10) key = ""
                    if (key.isNotBlank()) key = AudioAnalyzer.toCamelot(key)

                    return Pair(bpm, key)
                } else {
                    raf.skipBytes(dataLength)
                }

                if (isLast) break
            }

            return Pair(bpm, key)
        } finally {
            raf.close()
        }
    }

    private fun parseVorbisComments(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val vendorLen = buf.int
            buf.position(buf.position() + vendorLen)
            val commentCount = buf.int

            for (i in 0 until commentCount) {
                if (buf.remaining() < 4) break
                val commentLen = buf.int
                if (buf.remaining() < commentLen) break
                val commentBytes = ByteArray(commentLen)
                buf.get(commentBytes)
                val comment = String(commentBytes, Charsets.UTF_8)

                val eqIndex = comment.indexOf('=')
                if (eqIndex > 0) {
                    val k = comment.substring(0, eqIndex)
                    val v = comment.substring(eqIndex + 1)
                    if (!result.containsKey(k)) {
                        result[k] = v
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "parseVorbisComments error: ${e.message}")
        }
        return result
    }

    fun readId3v2Tags(filePath: String): Pair<Int, String> {
        val raf = RandomAccessFile(filePath, "r")
        try {
            val magic = ByteArray(3)
            raf.read(magic)
            if (String(magic) != "ID3") return Pair(0, "")

            raf.skipBytes(3)

            val sizeBytes = ByteArray(4)
            raf.read(sizeBytes)
            val tagSize = ((sizeBytes[0].toInt() and 0x7F) shl 21) or
                    ((sizeBytes[1].toInt() and 0x7F) shl 14) or
                    ((sizeBytes[2].toInt() and 0x7F) shl 7) or
                    (sizeBytes[3].toInt() and 0x7F)

            val tagData = ByteArray(tagSize)
            raf.read(tagData)

            var bpm = 0
            var key = ""
            var pos = 0

            while (pos + 10 <= tagData.size) {
                val frameId = String(tagData, pos, 4, Charsets.ISO_8859_1)
                if (frameId[0] < 'A' || frameId[0] > 'Z') break

                val frameSize = ((tagData[pos + 4].toInt() and 0xFF) shl 24) or
                        ((tagData[pos + 5].toInt() and 0xFF) shl 16) or
                        ((tagData[pos + 6].toInt() and 0xFF) shl 8) or
                        (tagData[pos + 7].toInt() and 0xFF)

                if (frameSize <= 0 || frameSize > tagData.size) break

                val frameData = tagData.copyOfRange(pos + 10, minOf(pos + 10 + frameSize, tagData.size))

                when (frameId) {
                    "TBPM" -> {
                        val text = decodeId3Text(frameData)
                        text.toIntOrNull()?.let { if (it in 20..300) bpm = it }
                    }
                    "TKEY" -> {
                        val text = decodeId3Text(frameData)
                        if (text.length in 1..10) key = AudioAnalyzer.toCamelot(text)
                    }
                }

                pos += 10 + frameSize
            }

            return Pair(bpm, key)
        } finally {
            raf.close()
        }
    }

    private fun decodeId3Text(data: ByteArray): String {
        if (data.isEmpty()) return ""
        return try {
            val encoding = data[0].toInt() and 0xFF
            val textBytes = data.copyOfRange(1, data.size)
            when (encoding) {
                0 -> String(textBytes, Charsets.ISO_8859_1).trimEnd('\u0000')
                1 -> String(textBytes, Charsets.UTF_16).trimEnd('\u0000')
                2 -> String(textBytes, Charsets.UTF_16BE).trimEnd('\u0000')
                3 -> String(textBytes, Charsets.UTF_8).trimEnd('\u0000')
                else -> String(textBytes, Charsets.UTF_8).trimEnd('\u0000')
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 【V8.96】读取 MP3 内嵌歌词：ID3v2 的 USLT 帧（Unsynced Lyrics）。
     *
     * 修复前 LyricsEmbedder 只「写」不「读」——写进去的 USLT 没人解析：
     *   - 本文件的 readId3v2Tags() 只认 TBPM/TKEY，且返回 Pair<Int,String> 装不下歌词；
     *   - LyricRepository.loadEmbedded() 的格式闸门只有 flac / wav，mp3 直接 skip；
     *   - LyricUtils 非 flac 分支走 MediaMetadataRetriever，其内容提取函数硬编码 return null。
     * 结果：MP3 内嵌歌词落盘成功但形同虚设，只能靠同名 .lrc 兜底。本函数补齐读取端。
     *
     * 支持 ID3v2.3（帧长 = 32bit big-endian）与 ID3v2.4（帧长 = synchsafe，7bit/字节）。
     * @return 歌词文本；无 ID3 标签 / 无 USLT / 布局不受支持时返回 null
     */
    fun readId3v2Lyrics(filePath: String): String? {
        val raf = try {
            RandomAccessFile(filePath, "r")
        } catch (e: Exception) {
            return null
        }
        try {
            if (raf.length() < 10) return null
            val head = ByteArray(10)
            raf.readFully(head)
            if (head[0] != 0x49.toByte() || head[1] != 0x44.toByte() || head[2] != 0x33.toByte()) return null
            val major = head[3].toInt() and 0xFF
            if (major != 3 && major != 4) return null
            val flags = head[5].toInt() and 0xFF
            // 扩展头会让帧区起点偏移；本读取器不解析扩展头，直接放弃以免误判帧边界
            if (flags and 0x40 != 0) return null
            val unsync = flags and 0x80 != 0
            val tagSize = ((head[6].toInt() and 0x7F) shl 21) or
                    ((head[7].toInt() and 0x7F) shl 14) or
                    ((head[8].toInt() and 0x7F) shl 7) or
                    (head[9].toInt() and 0x7F)
            val avail = (raf.length() - 10L).coerceAtLeast(0L)
            val len = minOf(tagSize.toLong(), avail).toInt()
            if (len <= 0) return null
            val tag = ByteArray(len)
            raf.readFully(tag)

            val synchsafe = major == 4
            var pos = 0
            while (pos + 10 <= tag.size) {
                if (tag[pos] == 0.toByte()) break // 填充区，结束
                val id = String(tag, pos, 4, Charsets.ISO_8859_1)
                if (id[0] < 'A' || id[0] > 'Z') break
                val size = if (synchsafe) {
                    ((tag[pos + 4].toInt() and 0x7F) shl 21) or
                            ((tag[pos + 5].toInt() and 0x7F) shl 14) or
                            ((tag[pos + 6].toInt() and 0x7F) shl 7) or
                            (tag[pos + 7].toInt() and 0x7F)
                } else {
                    ((tag[pos + 4].toInt() and 0xFF) shl 24) or
                            ((tag[pos + 5].toInt() and 0xFF) shl 16) or
                            ((tag[pos + 6].toInt() and 0xFF) shl 8) or
                            (tag[pos + 7].toInt() and 0xFF)
                }
                val bodyStart = pos + 10
                if (size <= 0 || bodyStart + size > tag.size) break
                if (id == "USLT" || id == "ULT") {
                    var body = tag.copyOfRange(bodyStart, bodyStart + size)
                    if (unsync) body = removeUnsync(body)
                    val text = decodeUsltBody(body)
                    if (!text.isNullOrBlank()) return text
                }
                pos = bodyStart + size
            }
            return null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "readId3v2Lyrics failed: ${e.message}")
            return null
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /**
     * USLT 帧体布局：encoding(1) | language(3) | content descriptor(NUL 终止) | lyrics
     * encoding: 0=ISO-8859-1, 1=UTF-16(BOM), 2=UTF-16BE, 3=UTF-8
     */
    private fun decodeUsltBody(body: ByteArray): String? {
        if (body.size < 4) return null
        val enc = body[0].toInt() and 0xFF
        var p = 4 // 跳过 encoding + language
        val end = body.size
        if (enc == 1 || enc == 2) {
            // UTF-16 描述符以双 NUL 结束，按 2 字节步进保持对齐
            while (p + 1 < end && !(body[p] == 0.toByte() && body[p + 1] == 0.toByte())) p += 2
            p += 2
        } else {
            while (p < end && body[p] != 0.toByte()) p++
            p += 1
        }
        if (p >= end) return null
        val textBytes = body.copyOfRange(p, end)
        return try {
            when (enc) {
                0 -> String(textBytes, Charsets.ISO_8859_1)
                1 -> String(textBytes, Charsets.UTF_16)
                2 -> String(textBytes, Charsets.UTF_16BE)
                else -> String(textBytes, Charsets.UTF_8)
            }.trimEnd('\u0000').trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /** ID3v2 整体 unsynchronisation：0xFF 0x00 还原为 0xFF */
    private fun removeUnsync(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val b = data[i]
            out.write(b.toInt())
            if (b == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    // ==================== MediaStore 扫描 ====================

    suspend fun rescanFromMediaStore(
        context: Context,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Song> {
        return withContext(Dispatchers.IO) {
            // 带 sink 时不再写全局 _scanProgress：调用方（修复媒体库）有自己的进度流，
            // 全局那份留给列表页的常规扫描，两边不互相顶。
            val reportGlobal = onProgress == null
            val musicList = mutableListOf<Song>()

            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATE_ADDED
            )

            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            val perm = context.checkSelfPermission("android.permission.READ_MEDIA_AUDIO")
            android.util.Log.d(TAG, "rescanFromMediaStore: READ_MEDIA_AUDIO = ${perm == android.content.pm.PackageManager.PERMISSION_GRANTED}")

            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
                android.util.Log.d(TAG, "rescanFromMediaStore: cursor=${cursor != null}, count=${cursor?.count ?: -1}")
                cursor?.use { c ->
                    val totalCount = c.count
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durationIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val dateAddedIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

                    var processed = 0
                    if (reportGlobal) _scanProgress.value = 0f
                    // [V8.103] 上次列表按路径索引：下面遇到「时长未知」的行先来这里捞已知值，
                    // 免得每扫一次都拿 MediaMetadataRetriever 现读一遍盘。
                    val prevByPath = songs.associateBy { it.filePath }
                    while (c.moveToNext()) {
                        // Emit scan progress every 10 songs
                        if (++processed % 10 == 0 && totalCount > 0) {
                            if (reportGlobal) _scanProgress.value = processed.toFloat() / totalCount
                            onProgress?.invoke(processed, totalCount)
                        }
                        val id = c.getLong(idIdx)
                        val title = c.getString(titleIdx) ?: "Unknown"
                        // [V8.93] 占位字面量（<unknown> 等）在此收敛为 ""，不让它进入缓存/分组/通知
                        val artist = sanitizeArtist(c.getString(artistIdx))
                        val album = sanitizeAlbum(c.getString(albumIdx))
                        val duration = c.getLong(durationIdx)
                        val path = c.getString(dataIdx) ?: ""
                        if (android.util.Log.isLoggable("SongRepository", android.util.Log.DEBUG) && path.any { it > '\u007f' }) {
                            android.util.Log.d("SongRepository", "PATHHEX: " + path + " => " + path.toByteArray(Charsets.UTF_8).joinToString(" ") { b -> "%02x".format(b) })
                        }
                        val mimeType = c.getString(mimeIdx) ?: ""
                        val dateAdded = if (dateAddedIdx >= 0) {
                            val v = c.getLong(dateAddedIdx)
                            if (v > 0L) v else {
                                // fallback: 文件修改时间
                                val fp = c.getString(dataIdx)
                                if (fp != null) java.io.File(fp).lastModified() / 1000 else 0L
                            }
                        } else {
                            // DATE_ADDED column not available: use file lastModified
                            val fp = c.getString(dataIdx)
                            if (fp != null) java.io.File(fp).lastModified() / 1000 else 0L
                        }

                        // 过滤短音频（铃声等），默认最小 150 秒
                        val minDurationSec = try {
                            context.getSharedPreferences("sdw_music_prefs", Context.MODE_PRIVATE)
                                .getInt("min_duration", 150)
                        } catch (e: Exception) { 150 }
                        // [V8.101] duration<=0 不能当「短音频」直接丢 —— 刚重建索引的行会被静默误杀。
                        // [V8.103b] 回归「之前的过滤规则」：旧码是 `if (duration < minSec*1000) continue`，
                        //   duration=0（读不出时长）同样按「太短」排除。V8.101 改成 `duration > 0 && ...`
                        //   后，读不出时长的行全部放行 —— 这才是「修复媒体库后什么都扫出来」的源头。
                        //   这里先把真值尽量读出来（①复用上次同路径时长 ②现读文件），仍读不出（<=0）
                        //   就按旧规则当「太短」排除；播放器不支持 DSD，这类文件本就没保留价值。
                        var effDuration = duration
                        if (effDuration <= 0) {
                            val cachedDur = prevByPath[path]?.duration ?: 0L
                            effDuration = if (cachedDur > 0) cachedDur else withTimeoutOrNull(8000L) {
                                probeDurationMs(path)
                            } ?: 0L
                        }
                        if (effDuration < minDurationSec * 1000L) continue

                        // 过滤Ringtone/去电文件名
                        val fileName = path.substringAfterLast("/")
                        if (fileName.contains("Ringtone") || fileName.contains("来电") || fileName.contains("去电")) continue
                        if (path.contains("/Music/Recorder/", ignoreCase = true) ||
                            path.contains("/Recordings/", ignoreCase = true) ||
                            path.contains("/Voice Recorder/", ignoreCase = true) ||
                            path.contains("/CallRecordings/", ignoreCase = true) ||
                            path.contains("/Call/", ignoreCase = true) ||
                            path.contains("/MicroMsg/", ignoreCase = true) ||
                            path.contains("/Notifications/", ignoreCase = true) ||
                            path.contains("/Ringtones/", ignoreCase = true) ||
                            path.contains("/Alarms/", ignoreCase = true) ||
                            path.contains("/Recorder/", ignoreCase = true) ||
                            path.contains("/record/", ignoreCase = true) ||
                            path.contains("/Sound_recorder/", ignoreCase = true) ||
                            path.contains("/Telephony/", ignoreCase = true)) continue

                        val contentUri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()
                        ).toString()

                        val albumUri = Uri.withAppendedPath(
                            Uri.parse("content://media/external/audio/albumart"),
                            c.getString(albumIdIdx)
                        ).toString()

                        val ext = path.substringAfterLast('.', "").lowercase()
                        val format = when {
                            // DSD formats (MIME type often inaccurate, check extension first)
                            ext in setOf("dsf", "dsd", "dff", "dxd") -> "DSD"
                            // Lossless Hi-Res: check extension first
                            ext in setOf("flac", "fla") -> "FLAC"
                            ext in setOf("wav", "wave") -> "WAV"
                            ext in setOf("aiff", "aif", "aifc") -> "AIFF"
                            ext == "ape" -> "APE"
                            ext == "wv" -> "WAVPACK"
                            ext in setOf("m4a", "mp4") && mimeType.contains("mp4", true) -> {
                                // ALAC detection: M4A container, need codec check for true ALAC
                                // For now, treat as M4A (user can visually identify ALAC in tags)
                                "M4A"
                            }
                            // Fall back to MIME type
                            mimeType.contains("flac", true) -> "FLAC"
                            mimeType.contains("ogg", true) || mimeType.contains("opus", true) -> "OPUS"
                            mimeType.contains("wav", true) -> "WAV"
                            mimeType.contains("aac", true) -> "AAC"
                            mimeType.contains("mp4", true) || mimeType.contains("m4a", true) -> "M4A"
                            mimeType.contains("mp3", true) -> "MP3"
                            else -> if (ext.isNotEmpty()) ext.uppercase() else "AUDIO"
                        }

                        var (bpm, songKey) = withTimeoutOrNull(8000L) {
                            readBpmAndKey(path)
                        } ?: run {
                            android.util.Log.w(TAG, "scan: readBpmAndKey skipped (timeout) for $path")
                            (0 to "")
                        }
                        if (bpm <= 0 && songKey.isBlank()) {
                            BpmKeyCache.get(path)?.let { cached ->
                                if (cached.first > 0) bpm = cached.first
                                if (cached.second.isNotBlank()) songKey = cached.second
                            }
                        }

                        // [V8.91] WAV 的内嵌标签优先于 MediaStore。
                        // AOSP 的 WAVExtractor 只认 "fmt " / "data"，媒体库对 WAV 只能拿文件名当
                        // 标题、拿不到歌手、拿文件夹名当专辑。创作者真正写的标签在 "id3 " 块里，
                        // 这里直接读出来覆盖，避免「歌手 = <unknown>、专辑 = WeiXin」这种退化值。
                        var taggedTitle = title
                        var taggedArtist = artist
                        var taggedAlbum = album
                        if (format == "WAV" && path.startsWith("/")) {
                            // 只在媒体库给的是「占位值」（文件名 / <unknown> / 文件夹名）时才覆盖，
                            // 避免把看起来正常的元数据冲掉。判据与 heal 完全一致。
                            val needT = looksLikeFileName(title, path)
                            val needA = looksLikeArtistFallback(artist, path)
                            val needB = looksLikeAlbumFallback(album, path)
                            if (needT || needA || needB) {
                                try {
                                    WavCoverReader.readTags(path)?.let { tags ->
                                        if (needT && tags.title.isNotBlank()) taggedTitle = tags.title
                                        if (needA && tags.artist.isNotBlank()) taggedArtist = tags.artist
                                        if (needB && tags.album.isNotBlank()) taggedAlbum = tags.album
                                    }
                                } catch (e: Exception) {
                                    // 纯附加信息，读不到就沿用媒体库的值，绝不让扫描失败
                                    android.util.Log.w(TAG, "WAV 内嵌标签读取失败: " + e.message)
                                }
                            }
                        }

                        musicList.add(Song(
                            id = id,
                            title = taggedTitle,
                            artist = taggedArtist,
                            album = taggedAlbum,
                            duration = if (effDuration > 0) effDuration else 0,
                            path = contentUri,
                            albumArtUri = albumUri,
                            format = format,
                            filePath = path,
                            folderPath = path.substringBeforeLast("/", ""),
                            bpm = bpm,
                            key = songKey,
                            dateAdded = dateAdded
                        ))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "rescanFromMediaStore error: ${e.message}", e)
                if (reportGlobal) _scanProgress.value = 0f
            }

            android.util.Log.d(TAG, "rescanFromMediaStore: scanned ${musicList.size} songs")

            if (reportGlobal) _scanProgress.value = 1f

            // 【V7.91】扫描结果为空时不覆盖缓存，保护已有数据
            if (musicList.isNotEmpty()) {
                // 【V8.0】合并上次播放时间 & Favorites状态，防止扫描后归零
                val existingMap = songs.associateBy { it.id }
                val favIds = getFavoriteIds()
                val merged = musicList.map { s ->
                    val existing = existingMap[s.id]
                    s.copy(
                        lastPlayedAt = existing?.lastPlayedAt ?: 0L,
                        isFavorite = s.id.toString() in favIds
                    )
                }
                // [V8.101] 并集而非替换：媒体库这次漏报、但文件仍在盘上的旧歌一律保留。
                // 背景：改写音频文件（嵌入封面/歌词）后 MediaStore 那行会失效掉出 audio 表，
                // 旧代码把 musicList 整体赋给 songs 并落盘 —— 少量行丢失就被永久写进缓存，
                // 表现为「歌曲数量减少、刷新也回不来」。判据是「文件是否还在盘上」，
                // 已授予 All files access，exists() 开销可忽略。
                val incomingPaths = HashSet<String>(merged.size)
                merged.forEach { if (it.filePath.isNotBlank()) incomingPaths.add(it.filePath) }
                // [V8.103] 并集保留也必须过一遍扫描过滤：否则旧缓存里被骗进来的短音频 / 录音
                // 会被每次重扫原样救回来，改最小时长也清不掉（污染不可逆）。
                // 时长未知的行现读一次真实值，读到了就写回，避免下次重扫再读（IO 只花一次）。
                val minSecUnion = minDurationSecNow(context)
                val carried = songs.mapNotNull { old ->
                    val fp = old.filePath
                    if (fp.isBlank() || fp in incomingPaths || !java.io.File(fp).exists()) {
                        null
                    } else {
                        val durMs = if (old.duration > 0) old.duration else probeDurationMs(fp)
                        when {
                            isExcludedByPathRules(fp) -> null
                            // [V8.106] 容器里带 video 轨的：不救回（清掉历史误入曲库的视频）
                            containsVideoStream(fp) -> null
                            durMs < minSecUnion * 1000L -> null
                            durMs != old.duration -> old.copy(duration = durMs)
                            else -> old
                        }
                    }
                }
                val union = if (carried.isEmpty()) merged else merged + carried
                if (carried.isNotEmpty()) {
                    android.util.Log.i(
                        TAG,
                        "rescanFromMediaStore: kept " + carried.size +
                            " song(s) missing from MediaStore but still on disk"
                    )
                }
                // [V8.90] 重扫同样不能冲掉 heal 好的封面 URI 与 WAV 文本标签
                songs = preserveHealedMetadata(union).sortedBy { it.title.lowercase() }
                dataVersion++
                _foldersVersion.value++
                val folders = buildFoldersFromSongs(songs)
                saveFoldersToCache(folders)
                saveSongsToDiskCache(songs)
                android.util.Log.i(TAG, "rescanFromMediaStore: ✓ cache saved (${songs.size} total / ${musicList.size} from MediaStore)")
            } else {
                android.util.Log.w(TAG, "rescanFromMediaStore: scanned empty, keeping existing cache (${songs.size} songs in memory)")
            }
            songs
        }
    }

    // ── [V8.101] 本地全盘扫描 + 媒体库修复 ───────────────────────────
    //
    // 为什么需要它：「封面下载 / 嵌入」是走 All files access 直接改写音频文件
    // （写 .tmp 再 rename 换掉 inode），size / mtime / inode 全变，但全项目没有任何一处
    // 通知媒体库（搜 MediaScannerConnection / scanFile / notifyChange 均为零命中）。
    // MediaStore 那行因此失效掉出 audio 表 → rescanFromMediaStore 查不到
    // → 歌曲从列表消失，文件却还在文件夹里。
    //
    // 这里提供「手动触发：遍历本地文件夹 → 通知媒体库重建 → 重扫 → 兜底合并」，
    // 既能把已经丢掉的索引批量补回来，也让索引再次损坏时不至于丢歌。

    /** 直接遍历本地文件夹时能认的音频扩展名 */
    // [V8.106] 移除了 "mp4"：它是容器，绝大多数 .mp4 是视频（相机录像 / 录屏 / 剧集），
    // 只按扩展名放行会把视频扫进曲库。真正的 mp4 纯音频极少（m4a 已覆盖），
    // 且即便漏了也有下面的【流验证】可兜。改由 containsVideoStream() 按真实轨道判。
    private val AUDIO_EXTS = setOf(
        "flac", "fla", "wav", "wave", "mp3", "m4a", "aac",
        "ape", "wv", "aiff", "aif", "aifc", "ogg", "opus",
        "dsf", "dff", "dsd", "dxd", "wma", "mka"
    )

    /** 遍历时不进入的目录名（系统 / 缓存 / 回收站，进了只会浪费时间） */
    private val SKIP_DIR_NAMES = setOf(
        "Android", "data", "obb", "LOST.DIR", "\$RECYCLE.BIN",
        "System Volume Information", ".thumbnails", "cache", "Cache"
    )

    /** 与媒体库扫描保持一致的路径黑名单（小写片段） */
    private val SKIP_PATH_FRAGMENTS = listOf(
        "/music/recorder/", "/recordings/", "/voice recorder/", "/callrecordings/",
        "/call/", "/micromsg/", "/notifications/", "/ringtones/", "/alarms/",
        "/recorder/", "/record/", "/sound_recorder/", "/telephony/"
    )

    /**
     * 本地扫描用的稳定 id。
     *
     * 绝不能拿 hashCode 直接当 id：MediaStore 的 _ID 是正整数且可能回收重用，
     * 撞上就会和真行互相顶掉。这里统一映射到负数区间（永不取 0），与媒体库 id 天然隔离。
     */
    private fun localSongId(path: String): Long {
        val h = path.lowercase().hashCode().toLong() and 0xFFFFFFFFL
        return -(h + 1L)
    }

    /**
     * 可能「容器里藏视频」的扩展名。只有这些才值得开 MediaExtractor 验流，
     * 纯音频扩展名（flac/mp3/wav/ape…）直接跳过，全盘遍历零额外开销。
     */
    private val VIDEO_CAPABLE_EXTS = setOf(
        "mp4", "m4a", "mka", "wma", "aac", "ogg", "opus",
        "3gp", "3gpp", "mov", "mkv", "avi", "webm", "ts", "flv", "mpg", "mpeg", "wmv"
    )

    /**
     * 【V8.106】判断文件里是否存在视频轨道。
     *
     * 背景：修复媒体库的兜底路径按「扩展名在 AUDIO_EXTS 里」就当音频补进列表，
     * 而 .mp4 视频恰好命中白名单、时长又远超最小时长，两道过滤都拦不住 ——
     * 视频被扫进曲库。这里按真实流轨判：只认「没有 video 轨」的才算音频。
     *
     * 失败时返回 false（保持旧行为，不因探测异常误杀正常音频）。
     */
    private fun containsVideoStream(path: String): Boolean {
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.length - 1) return false
        val ext = path.substring(dot + 1).lowercase()
        if (ext !in VIDEO_CAPABLE_EXTS) return false
        return try {
            // 【V8.429】收敛到单线程闸门（媒体库修复会并发扫大量文件，
            // 与其它探测交错拆卸内部 codec 会触发 native abort；详见 MediaProbe 类注释）。
            com.sdw.music.player.core.audio.analysis.MediaProbe.onProbeThread {
                val ex = android.media.MediaExtractor()
                try {
                    ex.setDataSource(path)
                    var hasVideo = false
                    for (i in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(i)
                            .getString(android.media.MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/")) { hasVideo = true; break }
                    }
                    hasVideo
                } catch (_: Throwable) {
                    false
                } finally {
                    try { ex.release() } catch (_: Throwable) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "containsVideoStream probe failed: " + e.message)
            false
        }
    }

    private fun isAudioFileForLocalScan(f: java.io.File): Boolean {
        val name = f.name
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1).lowercase()
        if (ext !in AUDIO_EXTS) return false
        if (name.contains("Ringtone") || name.contains("来电") || name.contains("去电")) return false
        val lower = f.absolutePath.lowercase()
        if (SKIP_PATH_FRAGMENTS.any { lower.contains(it) }) return false
        // [V8.106] 容器型扩展名再验一次真实轨道：带 video 轨的（相机录像 / 录屏）不算音频
        if (containsVideoStream(f.absolutePath)) return false
        return true
    }

    // ── [V8.103] 扫描过滤判据（并集保留 / 修复兜底共用同一套） ──────────────
    //
    // 教训：判据必须只有一处。V8.101 把「是否出现在重扫结果里」当成了「媒体库认不认识」，
    // 而重扫结果本身是已过滤的 —— 于是被时长 / 铃声名 / 路径黑名单筛掉的行，
    // 全被当成「媒体库不认」补回列表，过滤规则整条失效（用户报「过滤规则都不生效了」）。

    /** 当前设置里的最小时长（秒），默认 150。 */
    private fun minDurationSecNow(context: Context): Int = try {
        context.getSharedPreferences("sdw_music_prefs", Context.MODE_PRIVATE)
            .getInt("min_duration", 150)
    } catch (e: Exception) { 150 }

    /**
     * 单条音频是否被**路径 / 文件名**规则排除（不含时长）。
     * 供并集保留与修复兜底共用，避免两处判据各写一套导致漂移。
     * 时长判据单独见 isTooShort —— 两者语义不同，不能合成一个函数。
     */
    private fun isExcludedByPathRules(path: String): Boolean {
        val fileName = path.substringAfterLast("/")
        if (fileName.contains("Ringtone") || fileName.contains("来电") || fileName.contains("去电")) return true
        val lower = path.lowercase()
        return SKIP_PATH_FRAGMENTS.any { lower.contains(it) }
    }

    /**
     * 时长判据：读不出时长（<=0）按「太短」排除 —— 与 V8.101 之前的原始规则一致
     * （旧码 `if (duration < minSec * 1000) continue`，duration=0 也被排除）。
     * 播放器不支持 DSD，这类读不出时长的文件本就播不了，没有保留价值。
     */
    private fun isTooShort(durationMs: Long, minDurationSec: Int): Boolean =
        durationMs < minDurationSec * 1000L

    /**
     * 读单个文件的真实时长（毫秒）。只用于「媒体库不认识的本地文件」这一小撮，
     * 不会拖慢全盘遍历。读不到（DSD 等平台不支持）返回 0，调用方按「太短」排除。
     */
    private fun probeDurationMs(path: String): Long {
        // 【V9.19】隔离线程 + 硬超时：MediaMetadataRetriever.setDataSource 在路径含特殊字符或文件
        // 损坏时，原生层会直接挂死、永不返回，且无任何可取消手段；旧实现走 MediaProbe 单线程，
        // 一旦卡住整个扫描停在这一首。这里改用一次性线程 + future.get 超时，挂死也只废掉这条
        // 临时线程，扫描主流程靠扫描循环里的 withTimeoutOrNull 兜底继续推进。
        return try {
            val task = java.util.concurrent.FutureTask<Long> {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(path)
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } catch (_: Throwable) {
                    0L
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            }
            val thread = Thread(task, "probe-dur-timeout")
            thread.start()
            try {
                task.get(8000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                android.util.Log.w(TAG, "probeDurationMs: timeout/aborted for $path")
                0L
            }
        } catch (e: Exception) { 0L }
    }

    /**
     * MediaStore 索引到的**全部**音频路径，不加任何过滤。
     * 只回答一个问题：「这一行媒体库到底认不认识」。修复兜底必须用它，
     * 不能用 rescan 的返回值（那是过滤后的），否则会把「被规则筛掉」误判成「媒体库不认」。
     */
    private fun queryMediaStoreAllPaths(context: Context): Set<String> {
        val out = HashSet<String>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val p = c.getString(idx)
                    if (!p.isNullOrBlank()) out.add(p)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "queryMediaStoreAllPaths failed: " + e.message)
        }
        return out
    }

    /**
     * 遍历所有可读存储卷，返回全部音频文件。
     *
     * 与 scanSafFolder 的区别：那个要用户手选一个 tree 且只扫单层；
     * 这里靠已授予的 All files access 直接走文件系统，一次拿全（含子目录），
     * 不依赖 MediaStore。
     */
    fun collectLocalAudioFiles(onProgress: ((Int) -> Unit)? = null): List<java.io.File> {
        val roots = LinkedHashSet<java.io.File>()
        try {
            val ext = android.os.Environment.getExternalStorageDirectory()
            if (ext != null) roots.add(ext)
        } catch (_: Exception) {}
        try {
            java.io.File("/storage").listFiles()?.forEach { d ->
                val n = d.name
                if (d.isDirectory && d.canRead() && n != "emulated" && n != "self") roots.add(d)
            }
        } catch (_: Exception) {}

        val out = ArrayList<java.io.File>()
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue
            try {
                root.walkTopDown()
                    .onEnter { dir ->
                        dir.name !in SKIP_DIR_NAMES && !java.io.File(dir, ".nomedia").exists()
                    }
                    .maxDepth(12)
                    .forEach { f ->
                        if (f.isFile && isAudioFileForLocalScan(f)) {
                            if (seen.add(f.absolutePath)) {
                                out.add(f)
                                // 全盘遍历没有已知总数，只报「已发现 N 个」给 UI 转圈用
                                if (out.size % 50 == 0) onProgress?.invoke(out.size)
                            }
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "collectLocalAudioFiles root=" + root.absolutePath + ": " + e.message)
            }
        }
        return out
    }

    /**
     * 手动触发：修复媒体库并重扫。
     *
     * @return 本地扫到的音频文件数（0 表示一个都没扫到，UI 可据此提示）
     */
    suspend fun repairMediaStore(context: Context): Int = withContext(Dispatchers.IO) {
        // 阶段 0：全盘遍历（无已知总数，只报已发现数，UI 用不确定进度）
        _repairProgress.value = RepairProgress(0, 0, 0)
        val files = collectLocalAudioFiles { n ->
            _repairProgress.value = RepairProgress(0, n, 0)
        }
        android.util.Log.i(TAG, "repairMediaStore: " + files.size + " audio file(s) on disk")
        if (files.isEmpty()) {
            _repairProgress.value = null
            return@withContext 0
        }

        // ① 逐批通知媒体库按新 size/mtime 重建这些行。
        //    分批而不是一次全丢：一次几百上千条会把 Binder 事务打爆
        //    （TransactionTooLarge），而且单批失败不至于连累全部。
        val paths = files.map { it.absolutePath }
        _repairProgress.value = RepairProgress(1, 0, paths.size)
        val chunk = 64
        var scanned = 0
        val lock = Object()
        var i = 0
        while (i < paths.size) {
            val part = ArrayList<String>(paths.subList(i, minOf(i + chunk, paths.size)))
            val latch = java.util.concurrent.CountDownLatch(part.size)
            try {
                android.media.MediaScannerConnection.scanFile(
                    context, part.toTypedArray(), null
                ) { _, _ ->
                    synchronized(lock) {
                        scanned++
                        _repairProgress.value = RepairProgress(1, scanned, paths.size)
                    }
                    latch.countDown()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "scanFile chunk failed: " + e.message)
                repeat(part.size) { latch.countDown() }
            }
            latch.await(45, java.util.concurrent.TimeUnit.SECONDS)
            i += chunk
        }
        android.util.Log.i(TAG, "repairMediaStore: scanFile notified " + scanned + "/" + paths.size)

        // ② 用刚修好的索引重扫一次，拿回正确的 _ID / 时长 / 专辑封面
        _repairProgress.value = RepairProgress(2, 0, 0)
        val after = rescanFromMediaStore(context) { done, total ->
            _repairProgress.value = RepairProgress(2, done, total)
        }

        // ③ 兜底：媒体库仍不认的文件（扫描失败 / 目录带 .nomedia 等）直接用本地信息
        //    补进来，宁可元数据粗糙，也不让歌凭空消失。
        //
        //    [V8.103] 判据不能只看 after —— after 是**已过扫描过滤**的结果，
        //    拿它当「媒体库认不认识」会把这个逻辑搞反：「媒体库本来有这行、只是被
        //    App 规则筛掉了」的文件会被当成「媒体库不认」全部补回列表，于是
        //    时长过滤 / 铃声名 / 路径黑名单在修复后被整条绕过。
        //    这里单独查一次 MediaStore 的 DATA 列（不加过滤）当作「媒体库认识」的全集。
        val msAllPaths = queryMediaStoreAllPaths(context)
        val minSecFallback = minDurationSecNow(context)
        val known = HashSet<String>(after.size + msAllPaths.size)
        after.forEach { if (it.filePath.isNotBlank()) known.add(it.filePath) }
        msAllPaths.forEach { known.add(it) }
        // 路径 / 铃声名先筛一遍；时长在下面逐条读，读不出按「太短」排除
        val leftovers = files.filter { f ->
            f.absolutePath !in known && !isExcludedByPathRules(f.absolutePath)
        }
        if (leftovers.isNotEmpty()) {
            val favIds = getFavoriteIds()
            val extra = ArrayList<Song>(leftovers.size)
            _repairProgress.value = RepairProgress(3, 0, leftovers.size)
            for ((li, f) in leftovers.withIndex()) {
                _repairProgress.value = RepairProgress(3, li, leftovers.size)
                val p = f.absolutePath
                // [V8.103b] 与主循环同一口径：路径/铃声名 + 时长（读不出按「太短」排除）。
                val durMs = probeDurationMs(p)
                // [V8.106] 再加一道【真实流轨】验证：.mp4 等容器带 video 轨的一律不进曲库
                if (isExcludedByPathRules(p) || containsVideoStream(p) || isTooShort(durMs, minSecFallback)) continue
                val ext = f.extension.lowercase()
                val format = when {
                    ext in setOf("dsf", "dsd", "dff", "dxd") -> "DSD"
                    ext in setOf("flac", "fla") -> "FLAC"
                    ext in setOf("wav", "wave") -> "WAV"
                    ext in setOf("aiff", "aif", "aifc") -> "AIFF"
                    ext == "ape" -> "APE"
                    ext == "wv" -> "WAVPACK"
                    ext in setOf("ogg", "opus") -> "OPUS"
                    ext in setOf("m4a", "mp4") -> "M4A"
                    ext == "mp3" -> "MP3"
                    ext.isNotEmpty() -> ext.uppercase()
                    else -> "AUDIO"
                }
                // WAV 能读出真正的内嵌标签 / 封面；其它格式这里先用文件名兜底
                var title = f.nameWithoutExtension
                var artist = ""
                var album = ""
                var art = ""
                if (format == "WAV") {
                    try {
                        WavCoverReader.readTags(p)?.let { t ->
                            if (t.title.isNotBlank()) title = t.title
                            if (t.artist.isNotBlank()) artist = t.artist
                            if (t.album.isNotBlank()) album = t.album
                        }
                        WavCoverReader.coverUriForFile(context, p)?.let { if (it.isNotBlank()) art = it }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "local WAV tags failed: " + e.message)
                    }
                }
                val sid = localSongId(p)
                extra.add(
                    Song(
                        id = sid,
                        title = title,
                        artist = sanitizeArtist(artist),
                        album = sanitizeAlbum(album),
                        // [V8.103b] 已为它读过一次时长；读不出（<=0）的行上面已被排除，
                        // 能走到这里的都有真值。
                        duration = durMs,
                        path = android.net.Uri.fromFile(f).toString(),
                        albumArtUri = art,
                        format = format,
                        filePath = p,
                        folderPath = f.parent ?: "",
                        dateAdded = f.lastModified() / 1000,
                        isFavorite = sid.toString() in favIds
                    )
                )
            }
            val combined = (after + extra).distinctBy { it.filePath }
            songs = preserveHealedMetadata(combined).sortedBy { it.title.lowercase() }
            dataVersion++
            _foldersVersion.value++
            saveFoldersToCache(buildFoldersFromSongs(songs))
            saveSongsToDiskCache(songs)
            android.util.Log.i(
                TAG,
                "repairMediaStore: recovered " + extra.size + " file(s) still unknown to MediaStore"
            )
        }

        _repairProgress.value = null
        files.size
    }

    /**
     * V8.87 / V8.91 Fill in covers AND text tags for WAV files the platform cannot report.
     *
     * A WAV carries both its picture and its tags in the "id3 " chunk, but AOSP's WAVExtractor
     * never reports kKeyAlbumArt and never reads tag text (it only handles "fmt " and "data"),
     * so MediaStore gives those rows no art, the FILE NAME as the title, no artist, and the
     * FOLDER as the album. No platform API can see the real values, which is why this reads
     * the container directly.
     *
     * Runs off the main thread and only touches WAV. Files already carrying a real URI / real
     * tags are skipped, so repeat launches are effectively free; covers additionally go through
     * the on-disk cache (positive and negative) keyed by path+size+mtime.
     */
    suspend fun healWavMetadata(context: Context): Int = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext 0
        // [V8.90] 只收集「id -> 新值」增量，最后应用到**当前最新**的 songs 上。
        // 原先直接 `songs = updated`（写回本函数开头那份快照），若解析期间发生重扫 /
        // 收藏同步 / setSongs，并发变更会被这份旧快照整体回退 —— 与 setSongs 的
        // preserveHealedMetadata 是同一类竞态的两个方向，这里一并加固。
        val newArt = HashMap<Long, String>()
        val newTags = HashMap<Long, Song>()
        songs.forEach { song ->
            // [V8.138] 不只 WAV：FLAC 的封面同样可能因「改写文件后媒体库索引失效」而消失
            // （保存/清除内嵌歌词即整文件重写），这里一并从容器里把图抽回来。
            val lowerPath = song.filePath.lowercase()
            val isWav = song.format.equals("WAV", true) ||
                lowerPath.endsWith(".wav") || lowerPath.endsWith(".wave")
            val isFlac = song.format.equals("FLAC", true) ||
                lowerPath.endsWith(".flac") || lowerPath.endsWith(".fla")
            if ((!isWav && !isFlac) || song.filePath.isBlank()) return@forEach
            val realPath = song.filePath
            if (!realPath.startsWith("/")) return@forEach

            // ---- 封面 ----
            // Important: the scanner ALWAYS writes a non-blank albumArtUri for MediaStore rows -
            // either content://media/external/audio/albumart/<ALBUM_ID> or, when the album is
            // unknown, the literal ".../albumart/null". That virtual table cannot serve WAV art
            // (AOSP never reports kKeyAlbumArt for WAV), so a non-blank URI here does NOT mean
            // the cover is already available. Only trust a URI that is not the albumart table.
            val existing = song.albumArtUri
            val pointsAtAlbumArtTable = existing.contains("/audio/albumart", ignoreCase = true)
            if (existing.isBlank() || pointsAtAlbumArtTable) {
                val uri = try {
                    if (isFlac) FlacCoverReader.coverUriForFile(context, realPath)
                    else WavCoverReader.coverUriForFile(context, realPath)
                } catch (e: Exception) {
                    null
                }
                if (!uri.isNullOrBlank()) newArt[song.id] = uri
            }

            // ---- 文本标签 ----
            // 只补「明显是占位值」的字段，绝不用内嵌标签覆盖看起来正常的媒体库值。
            val needTitle = looksLikeFileName(song.title, realPath)
            val needArtist = looksLikeArtistFallback(song.artist, realPath)
            val needAlbum = looksLikeAlbumFallback(song.album, realPath)
            if (needTitle || needArtist || needAlbum) {
                val tags = try {
                    WavCoverReader.readTags(realPath)
                } catch (e: Exception) {
                    null
                }
                if (tags != null) {
                    var fixed = song
                    if (needTitle && tags.title.isNotBlank()) fixed = fixed.copy(title = tags.title)
                    if (needArtist && tags.artist.isNotBlank()) fixed = fixed.copy(artist = tags.artist)
                    if (needAlbum && tags.album.isNotBlank()) fixed = fixed.copy(album = tags.album)
                    if (fixed !== song) newTags[song.id] = fixed
                }
            }
        }

        val fixed = newArt.size + newTags.size
        if (fixed > 0) {
            songs = songs.map { s ->
                var out = s
                newArt[s.id]?.let { out = out.copy(albumArtUri = it) }
                newTags[s.id]?.let { t ->
                    out = out.copy(title = t.title, artist = t.artist, album = t.album)
                }
                out
            }
            dataVersion++
            saveSongsToDiskCache(songs)
            android.util.Log.i(
                TAG,
                "healWavMetadata: filled " + newArt.size + " cover(s), " +
                    newTags.size + " tag set(s)"
            )
        }
        fixed
    }

    // ==================== SAF 导入 ====================

    suspend fun scanSafFolder(context: Context, treeUri: android.net.Uri): Int = withContext(Dispatchers.IO) {
        val added = mutableListOf<Song>()
        try {
            val docUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            )
            val docProjection = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            context.contentResolver.query(docUri, docProjection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2) ?: continue
                    if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (!mimeType.startsWith("audio/")) continue
                    val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val fileName = displayName
                    val song = Song(
                        id = fileUri.hashCode().toLong().let { if (it < 0) -it else it },
                        title = fileName.substringBeforeLast("."),
                        artist = "",
                        album = "",
                        duration = 0,
                        path = fileUri.toString(),
                        albumArtUri = "",
                        format = fileName.substringAfterLast(".").uppercase(),
                        filePath = fileUri.toString(),
                        folderPath = ""
                    )
                    if (songs.none { it.title == song.title && it.path == song.path }) {
                        added.add(song)
                    }
                }
            }
            android.util.Log.d(TAG, "scanSafFolder: found ${added.size} audio files")
            if (added.isNotEmpty()) {
                val merged = (songs + added).distinctBy { it.path }.sortedBy { it.title.lowercase() }
                setSongs(merged)
                saveFoldersToCache(buildFoldersFromSongs(merged))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "scanSafFolder error", e)
        }
        added.size
    }

    // ==================== Favorites管理 ====================
    private const val PREFS_FAVORITES = "sdw_favorites"
    private const val KEY_FAVORITE_IDS = "favorite_ids"

    private fun getFavoriteIds(): Set<String> {
        val ctx = appCtx ?: return emptySet()
        return ctx.getSharedPreferences(PREFS_FAVORITES, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITE_IDS, emptySet()) ?: emptySet()
    }

    private fun saveFavoriteIds(ids: Set<String>) {
        val ctx = appCtx ?: return
        ctx.getSharedPreferences(PREFS_FAVORITES, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_FAVORITE_IDS, ids).apply()
    }

    fun isFavorite(songId: Long): Boolean = songId.toString() in getFavoriteIds()

    fun toggleFavorite(songId: Long): Boolean {
        val ids = getFavoriteIds().toMutableSet()
        val key = songId.toString()
        val nowFavorite = if (key in ids) {
            ids.remove(key)
            false
        } else {
            ids.add(key)
            true
        }
        saveFavoriteIds(ids)
        // 同步到内存中的 songs 列表
        songs = songs.map { if (it.id == songId) it.copy(isFavorite = nowFavorite) else it }
        dataVersion++
        return nowFavorite
    }

    fun getFavoriteSongs(): List<Song> = songs.filter { it.isFavorite || it.id.toString() in getFavoriteIds() }

    fun getFavoriteCount(): Int = getFavoriteIds().size

    /** 扫描后同步Favorites状态到 Song 列表 */
    fun syncFavoritesToSongs() {
        val favIds = getFavoriteIds()
        if (favIds.isEmpty()) return
        songs = songs.map { song ->
            if (song.id.toString() in favIds && !song.isFavorite) song.copy(isFavorite = true)
            else if (song.id.toString() !in favIds && song.isFavorite) song.copy(isFavorite = false)
            else song
        }
    }
}

