package com.sdw.music.player.di
import com.sdw.music.player.core.audio.playback.MusicService

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object MusicModule {
    // Will provide SongRepository, MusicService bindings, etc.
}
