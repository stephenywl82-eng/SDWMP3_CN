package com.sdw.music.player.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object SongList : Screen("songList")
    data object Player : Screen("player")
    data object LyricFullscreen : Screen("lyricFullscreen")
    data object Settings : Screen("settings")
    data object FolderList : Screen("folderList")
    data object PlaylistList : Screen("playlistList")
    data object PlaylistDetail : Screen("playlistDetail/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlistDetail/$playlistId"
    }
    data object Equalizer : Screen("equalizer")
    data object Mseb : Screen("mseb")
    data object LyricSearch : Screen("lyricSearch/{songId}/{songTitle}/{songArtist}") {
        fun createRoute(songId: Long, title: String, artist: String) =
            "lyricSearch/$songId/$title/$artist"
    }
    data object AlbumList : Screen("albumList")
    data object AlbumSong : Screen("albumSong/{albumName}") {
        fun createRoute(albumName: String) = "albumSong/${Uri.encode(albumName)}"
    }
    data object ArtistList : Screen("artistList")
    data object ArtistSong : Screen("artistSong/{artistName}") {
        fun createRoute(artistName: String) = "artistSong/${Uri.encode(artistName)}"
    }
    data object AudioDiagnostic : Screen("audioDiagnostic")
    data object AudioQuality : Screen("audioQuality")
    data object SongPicker : Screen("songPicker/{playlistId}") {
        fun createRoute(playlistId: Long) = "songPicker/$playlistId"
    }
}
