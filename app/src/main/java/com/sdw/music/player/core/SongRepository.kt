package com.sdw.music.player

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    private var songs: List<Song> = emptyList()
    @Volatile
    var dataVersion: Long = 0
        private set

    /** Folders列表版本号，每次Folders内容变化 +1，供 UI 观察 */
    private val _foldersVersion = MutableStateFlow(0L)
    val foldersVersion: StateFlow<Long> = _foldersVersion.asStateFlow()

    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()

    private var appCtx: Context? = null
    private var lastSavedHash: Int = 0  // debounce saveSongsToDiskCache


    fun init(context: Context) {
        appCtx = context.applicationContext
        PlaylistManager.init(context)
        // 启动时先从磁盘缓存恢复歌曲列表（毫秒级）
        loadSongsFromDiskCache()
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
            for (i in 0 until arr.length()) {
                parseSongFromJson(arr.getJSONObject(i))?.let { list.add(it) }
            }
            if (list.isNotEmpty()) {
                songs = list
                dataVersion++
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
                artist = obj.optString("artist", "Unknown Artist"),
                album = obj.optString("album", "Unknown Album"),
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

    fun setSongs(songList: List<Song>) {
        songs = songList
        dataVersion++
        saveSongsToDiskCache(songList)
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

            while (true) {
                val header = ByteArray(4)
                if (raf.read(header) < 4) break

                val blockType = header[0].toInt() and 0x7F
                val isLast = (header[0].toInt() and 0x80) != 0

                val dataLength = ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)

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

    // ==================== MediaStore 扫描 ====================

    suspend fun rescanFromMediaStore(context: Context): List<Song> {
        return withContext(Dispatchers.IO) {
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
                    _scanProgress.value = 0f
                    while (c.moveToNext()) {
                        // Emit scan progress every 10 songs
                        if (++processed % 10 == 0 && totalCount > 0) {
                            _scanProgress.value = processed.toFloat() / totalCount
                        }
                        val id = c.getLong(idIdx)
                        val title = c.getString(titleIdx) ?: "Unknown"
                        val artist = c.getString(artistIdx) ?: "Unknown Artist"
                        val album = c.getString(albumIdx) ?: "Unknown Album"
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
                        if (duration < minDurationSec * 1000L) continue

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

                        var (bpm, songKey) = readBpmAndKey(path)
                        if (bpm <= 0 && songKey.isBlank()) {
                            BpmKeyCache.get(path)?.let { cached ->
                                if (cached.first > 0) bpm = cached.first
                                if (cached.second.isNotBlank()) songKey = cached.second
                            }
                        }

                        musicList.add(Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = if (duration > 0) duration else 0,
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
                _scanProgress.value = 0f
            }

            android.util.Log.d(TAG, "rescanFromMediaStore: scanned ${musicList.size} songs")

            _scanProgress.value = 1f

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
                songs = merged.sortedBy { it.title.lowercase() }
                dataVersion++
                _foldersVersion.value++
                val folders = buildFoldersFromSongs(songs)
                saveFoldersToCache(folders)
                saveSongsToDiskCache(songs)
                android.util.Log.i(TAG, "rescanFromMediaStore: ✓ cache saved (${musicList.size} songs)")
            } else {
                android.util.Log.w(TAG, "rescanFromMediaStore: scanned empty, keeping existing cache (${songs.size} songs in memory)")
            }
            songs
        }
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
                        artist = "Unknown Artist",
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

