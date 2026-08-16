package com.sdw.music.player.core.model

/**
 * Persisted playback state stored via DataStore.
 * Used to restore full playback state after process death.
 */
data class PlayerPersistedState(
    val lastSongId: Long = -1L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
    val playbackPositionMs: Long = 0L,
    val lastQueueIds: List<Long> = emptyList(),
    val dspMode: Int = -1
)
