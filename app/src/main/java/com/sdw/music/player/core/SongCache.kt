package com.sdw.music.player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 歌曲列表持久化缓存
 * 冷启动时直接从 SharedPreferences 反序列化，跳过 MediaStore 扫描
 * 后台静默扫描 MediaStore 比对，有变化才Refresh
 */
object SongCache {
    private const val PREFS_NAME = "song_list_cache"
    private const val KEY_SONGS = "songs_json"
    private const val KEY_VERSION = "cache_version"
    private const val CACHE_VERSION = 1  // 数据结构变更时 bump
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * 从缓存加载歌曲列表
     * @return 缓存的歌曲列表，缓存无效时Back null
     */
    fun load(): List<Song>? {
        try {
            val p = prefs ?: return null
            val version = p.getInt(KEY_VERSION, 0)
            if (version != CACHE_VERSION) return null

            val json = p.getString(KEY_SONGS, "") ?: ""
            if (json.isBlank()) return null

            val arr = JSONArray(json)
            if (arr.length() == 0) return null

            val songs = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                songs.add(Song(
                    id = obj.optLong("id", 0),
                    title = obj.optString("title", ""),
                    artist = obj.optString("artist", ""),
                    album = obj.optString("album", ""),
                    duration = obj.optLong("duration", 0),
                    path = obj.optString("path", ""),
                    albumArtUri = obj.optString("albumArtUri", ""),
                    format = obj.optString("format", ""),
                    filePath = obj.optString("filePath", ""),
                    folderPath = obj.optString("folderPath", ""),
                    bpm = obj.optInt("bpm", 0),
                    key = obj.optString("key", ""),
                    genre = obj.optString("genre", ""),
                    dateAdded = obj.optLong("dateAdded", 0)
                ))
            }
            android.util.Log.d("SongCache", "Loaded ${songs.size} songs from cache")
            return songs
        } catch (e: Exception) {
            android.util.Log.w("SongCache", "Failed to load cache: ${e.message}")
            return null
        }
    }

    /**
     * Save歌曲列表到缓存
     */
    fun save(songs: List<Song>) {
        try {
            val arr = JSONArray()
            for (song in songs) {
                val obj = JSONObject()
                obj.put("id", song.id)
                obj.put("title", song.title)
                obj.put("artist", song.artist)
                obj.put("album", song.album)
                obj.put("duration", song.duration)
                obj.put("path", song.path)
                obj.put("albumArtUri", song.albumArtUri)
                obj.put("format", song.format)
                obj.put("filePath", song.filePath)
                obj.put("folderPath", song.folderPath)
                obj.put("bpm", song.bpm)
                obj.put("key", song.key)
                obj.put("genre", song.genre)
                obj.put("dateAdded", song.dateAdded)
                arr.put(obj)
            }
            prefs?.edit()
                ?.putString(KEY_SONGS, arr.toString())
                ?.putInt(KEY_VERSION, CACHE_VERSION)
                ?.apply()
            android.util.Log.d("SongCache", "Saved ${songs.size} songs to cache")
        } catch (e: Exception) {
            android.util.Log.w("SongCache", "Failed to save cache: ${e.message}")
        }
    }

    /**
     * 清空缓存（Refresh按钮时调用）
     */
    fun clear() {
        prefs?.edit()?.clear()?.apply()
        android.util.Log.d("SongCache", "Cache cleared")
    }

    /**
     * 缓存条目数
     */
    fun size(): Int {
        val json = prefs?.getString(KEY_SONGS, "") ?: ""
        if (json.isBlank()) return 0
        return try { JSONArray(json).length() } catch (_: Exception) { 0 }
    }
}

