package com.sdw.music.player.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sdw.music.player.Folder
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SongListState(
    val songs: List<Song> = emptyList(),
    val filteredSongs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isLoading: Boolean = true,
    val currentPlayingIndex: Int = -1,
    val accentColor: Long = 0L,  // 0 = no cover color, falls back to system primary
    val songCount: Int = 0,
    val folders: List<Folder> = emptyList(),
    val favoriteCount: Int = 0
)

sealed class SongListIntent {
    data class Search(val query: String) : SongListIntent()
    data class PlaySong(val index: Int) : SongListIntent()
    data object ToggleSearch : SongListIntent()
    data class FilterByTag(val tag: String) : SongListIntent()
    data object Refresh : SongListIntent()
    data class NavigateToFolder(val folderPath: String) : SongListIntent()
    data object ShowFavorites : SongListIntent()
}

class SongListViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SongListState())
    val state: StateFlow<SongListState> = _state.asStateFlow()

    init {
        loadSongs()
    }

    private fun loadSongs() {
        // 确保 SongRepository 已初始化（冷启动也安全）
        SongRepository.init(getApplication())
        // === 第一层：先显示缓存数据（毫秒级，秒On UI）===
        val cachedSongs = SongRepository.getSongs()
        if (cachedSongs.isNotEmpty()) {
            SongRepository.syncFavoritesToSongs()
            val favCount = SongRepository.getFavoriteCount()
            val folders = SongRepository.getFolders()
            _state.update {
                it.copy(
                    songs = cachedSongs,
                    filteredSongs = cachedSongs,
                    isLoading = false,
                    songCount = cachedSongs.size,
                    folders = folders,
                    favoriteCount = favCount
                )
            }
        } else {
            _state.update { it.copy(isLoading = true) }
        }

        // === 第二层：后台异步扫描 MediaStore（不阻塞 UI）===
        viewModelScope.launch {
            try {
                val freshSongs = SongRepository.rescanFromMediaStore(getApplication())
                SongRepository.syncFavoritesToSongs()

                // === 第三层：只有数据变化才更新 UI ===
                if (freshSongs != cachedSongs) {
                    val favCount = SongRepository.getFavoriteCount()
                    val folders = SongRepository.getFolders()
                    _state.update {
                        it.copy(
                            songs = freshSongs,
                            filteredSongs = freshSongs,
                            isLoading = false,
                            songCount = freshSongs.size,
                            folders = folders,
                            favoriteCount = favCount
                        )
                    }
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun handleIntent(intent: SongListIntent) {
        when (intent) {
            is SongListIntent.Search -> {
                _state.update { state ->
                    val filtered = if (intent.query.isBlank()) {
                        state.songs
                    } else {
                        state.songs.filter { song ->
                            song.title.contains(intent.query, ignoreCase = true) ||
                            song.artist.contains(intent.query, ignoreCase = true) ||
                            song.album.contains(intent.query, ignoreCase = true)
                        }
                    }
                    state.copy(searchQuery = intent.query, filteredSongs = filtered)
                }
            }
            is SongListIntent.PlaySong -> {
                _state.update { it.copy(currentPlayingIndex = intent.index) }
            }
            is SongListIntent.ToggleSearch -> {
                _state.update { state ->
                    state.copy(
                        isSearching = !state.isSearching,
                        searchQuery = "",
                        filteredSongs = if (!state.isSearching) state.songs else state.filteredSongs
                    )
                }
            }
            is SongListIntent.FilterByTag -> {
                // TODO: Filter by Hi-Res / Remix / etc
            }
            is SongListIntent.Refresh -> {
                loadSongs()
            }
            is SongListIntent.NavigateToFolder -> {
                // Will be handled by navigation in UI
            }
            is SongListIntent.ShowFavorites -> {
                val favSongs = SongRepository.getFavoriteSongs()
                _state.update { it.copy(filteredSongs = favSongs) }
            }
        }
    }

    fun refresh() {
        loadSongs()
    }
}
