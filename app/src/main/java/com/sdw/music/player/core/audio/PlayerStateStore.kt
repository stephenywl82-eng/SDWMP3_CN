package com.sdw.music.player.core.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sdw.music.player.core.model.PlayerPersistedState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "player_state")

@Singleton
class PlayerStateStore @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private val KEY_LAST_SONG_ID = longPreferencesKey("last_song_id")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val KEY_REPEAT_MODE = intPreferencesKey("repeat_mode")
        private val KEY_PLAYBACK_POSITION_MS = longPreferencesKey("playback_position_ms")
        private val KEY_LAST_QUEUE_IDS = stringPreferencesKey("last_queue_ids")
        private val KEY_DSP_MODE = intPreferencesKey("dsp_mode")
    }

    private val store: DataStore<Preferences> get() = appContext.dataStore

    /** Save full player state (called when song changes or settings change). */
    suspend fun saveState(
        songId: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
        positionMs: Long,
        queueIds: List<Long>,
        dspMode: Int
    ) {
        store.edit { prefs ->
            prefs[KEY_LAST_SONG_ID] = songId
            prefs[KEY_SHUFFLE_ENABLED] = shuffleEnabled
            prefs[KEY_REPEAT_MODE] = repeatMode
            prefs[KEY_PLAYBACK_POSITION_MS] = positionMs
            prefs[KEY_LAST_QUEUE_IDS] = queueIds.joinToString(",")
            prefs[KEY_DSP_MODE] = dspMode
        }
    }

    /** Lightweight save during playback — song ID + position only. */
    suspend fun saveQuickState(songId: Long, positionMs: Long) {
        store.edit { prefs ->
            prefs[KEY_LAST_SONG_ID] = songId
            prefs[KEY_PLAYBACK_POSITION_MS] = positionMs
        }
    }

    /** Stream of persisted state as Flow. */
    val stateFlow: Flow<PlayerPersistedState> = store.data.map { prefs ->
        val queueStr = prefs[KEY_LAST_QUEUE_IDS] ?: ""
        PlayerPersistedState(
            lastSongId = prefs[KEY_LAST_SONG_ID] ?: -1L,
            shuffleEnabled = prefs[KEY_SHUFFLE_ENABLED] ?: false,
            repeatMode = prefs[KEY_REPEAT_MODE] ?: 0,
            playbackPositionMs = prefs[KEY_PLAYBACK_POSITION_MS] ?: 0L,
            lastQueueIds = if (queueStr.isNotBlank()) {
                queueStr.split(",").mapNotNull { it.toLongOrNull() }
            } else emptyList(),
            dspMode = prefs[KEY_DSP_MODE] ?: -1
        )
    }

    /** Blocking read for synchronous access at init time. */
    fun loadStateBlocking(): PlayerPersistedState = runBlocking {
        stateFlow.first()
    }

    /** Suspend read for coroutine contexts. */
    suspend fun loadState(): PlayerPersistedState = stateFlow.first()
}
