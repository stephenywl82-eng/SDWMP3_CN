package com.sdw.music.player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义歌单数据模型
 */
data class Playlist(
    val id: Long,              // 唯一 ID（时间戳）
    val name: String,          // 歌单名称
    val songIds: List<Long>,   // 歌曲 ID 列表（有序）
    val createdAt: Long,       // 创建时间
    val updatedAt: Long        // 更新时间
)

/**
 * 歌单管理器 — 单例，SharedPreferences + JSON 持久化
 */
object PlaylistManager {
    private const val PREFS_NAME = "sdw_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"
    private var playlists: MutableList<Playlist> = mutableListOf()
    private var prefs: SharedPreferences? = null

    // 监听器
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromDisk()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        saveToDisk()
        listeners.forEach { it() }
    }

    // ─── CRUD ───

    fun getPlaylists(): List<Playlist> {
        val result = playlists.toList()
        android.util.Log.d("PlaylistManager", "getPlaylists: returning ${result.size} playlists")
        result.forEach { pl ->
            android.util.Log.d("PlaylistManager", "  - ${pl.name}: ${pl.songIds.size} songs")
        }
        return result
    }

    fun getPlaylist(id: Long): Playlist? = playlists.find { it.id == id }

    fun createPlaylist(name: String): Playlist {
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = now,
            name = name.trim(),
            songIds = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        playlists.add(0, playlist)  // 新歌单置顶
        notifyChanged()
        return playlist
    }

    fun renamePlaylist(id: Long, newName: String): Boolean {
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return false
        playlists[index] = playlists[index].copy(
            name = newName.trim(),
            updatedAt = System.currentTimeMillis()
        )
        notifyChanged()
        return true
    }

    fun deletePlaylist(id: Long): Boolean {
        val removed = playlists.removeAll { it.id == id }
        if (removed) notifyChanged()
        return removed
    }

    /**
     * 添加歌曲到歌单（去重，追加到末尾）
     */
    fun addSongToPlaylist(playlistId: Long, songId: Long): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val pl = playlists[index]
        if (songId in pl.songIds) return false  // 已存在
        playlists[index] = pl.copy(
            songIds = pl.songIds + songId,
            updatedAt = System.currentTimeMillis()
        )
        notifyChanged()
        return true
    }

    /**
     * 批量添加歌曲到歌单
     */
    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>): Int {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) {
            android.util.Log.e("PlaylistManager", "addSongsToPlaylist: playlist $playlistId not found")
            return 0
        }
        val pl = playlists[index]
        val newIds = songIds.filter { it !in pl.songIds }
        if (newIds.isEmpty()) {
            android.util.Log.d("PlaylistManager", "addSongsToPlaylist: no new songs to add")
            return 0
        }
        playlists[index] = pl.copy(
            songIds = pl.songIds + newIds,
            updatedAt = System.currentTimeMillis()
        )
        android.util.Log.d("PlaylistManager", "addSongsToPlaylist: added ${newIds.size} songs to ${pl.name}, total=${playlists[index].songIds.size}")
        notifyChanged()
        return newIds.size
    }

    /**
     * 从歌单移除歌曲
     */
    fun removeSongFromPlaylist(playlistId: Long, songId: Long): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val pl = playlists[index]
        if (songId !in pl.songIds) return false
        playlists[index] = pl.copy(
            songIds = pl.songIds - songId,
            updatedAt = System.currentTimeMillis()
        )
        notifyChanged()
        return true
    }

    /**
     * 移动歌单内歌曲顺序
     */
    fun moveSongInPlaylist(playlistId: Long, fromPosition: Int, toPosition: Int): Boolean {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return false
        val pl = playlists[index]
        if (fromPosition !in pl.songIds.indices || toPosition !in pl.songIds.indices) return false
        val mutableIds = pl.songIds.toMutableList()
        val item = mutableIds.removeAt(fromPosition)
        mutableIds.add(toPosition, item)
        playlists[index] = pl.copy(
            songIds = mutableIds,
            updatedAt = System.currentTimeMillis()
        )
        notifyChanged()
        return true
    }

    /**
     * 获取歌单中的完整 Song 对象列表
     */
    fun getSongsInPlaylist(playlistId: Long): List<Song> {
        val pl = getPlaylist(playlistId) ?: return emptyList()
        val allSongs = SongRepository.getSongs()
        val songMap = allSongs.associateBy { it.id }
        return pl.songIds.mapNotNull { songMap[it] }
    }

    // ─── 持久化 ───

    private fun loadFromDisk() {
        val json = prefs?.getString(KEY_PLAYLISTS, null)
        if (json == null) {
            android.util.Log.d("PlaylistManager", "loadFromDisk: no saved data")
            return
        }
        try {
            val arr = JSONArray(json)
            playlists = (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    val songIdsArr = obj.getJSONArray("songIds")
                    val songIds = (0 until songIdsArr.length()).map { songIdsArr.getLong(it) }
                    android.util.Log.d("PlaylistManager", "Loaded playlist: ${obj.getString("name")}, songs=${songIds.size}")
                    Playlist(
                        id = obj.getLong("id"),
                        name = obj.getString("name"),
                        songIds = songIds,
                        createdAt = obj.getLong("createdAt"),
                        updatedAt = obj.getLong("updatedAt")
                    )
                } catch (e: Exception) {
                    android.util.Log.e("PlaylistManager", "Error loading playlist item: ${e.message}")
                    null
                }
            }.toMutableList()
            android.util.Log.d("PlaylistManager", "loadFromDisk: loaded ${playlists.size} playlists")
        } catch (e: Exception) {
            android.util.Log.e("PlaylistManager", "Error loading playlists: ${e.message}")
            playlists = mutableListOf()
        }
    }

    private fun saveToDisk() {
        val arr = JSONArray()
        for (pl in playlists) {
            val obj = JSONObject().apply {
                put("id", pl.id)
                put("name", pl.name)
                put("songIds", JSONArray(pl.songIds))
                put("createdAt", pl.createdAt)
                put("updatedAt", pl.updatedAt)
            }
            arr.put(obj)
            android.util.Log.d("PlaylistManager", "Saving playlist: ${pl.name}, songs=${pl.songIds.size}")
        }
        val jsonString = arr.toString()
        prefs?.edit()?.putString(KEY_PLAYLISTS, jsonString)?.apply()
        android.util.Log.d("PlaylistManager", "saveToDisk: saved ${playlists.size} playlists, json length=${jsonString.length}")
    }
    /**
     * 获取歌单中所有歌曲对象
     */
    fun getPlaylistSongs(playlistId: Long): List<com.sdw.music.player.Song> {
        val playlist = playlists.find { it.id == playlistId } ?: return emptyList()
        return playlist.songIds.mapNotNull { id -> SongRepository.getSongById(id) }
    }

    /**
     * 添加歌曲到歌单（兼容方法名，供 UI 层调用）
     */
    fun addToPlaylist(playlistId: Long, songId: Long): Boolean {
        return addSongToPlaylist(playlistId, songId)
    }

}
