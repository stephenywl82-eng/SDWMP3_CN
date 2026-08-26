package com.sdw.music.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SDWApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 恢复用户选择的语言（必须在任何 UI 初始化前）
        AppLanguageManager.applyStored(this)
        // 恢复用户选择的主题（浅色/深色）
        ThemeManager.init(this)
        // 恢复自定义壁纸设置（背景/模糊/压暗/取色感知）
        WallpaperManager.init(this)
        // Initialize SongRepository with application context
        SongRepository.init(this)
        // Load persisted palette cache from disk
        MemoryManager.loadPaletteFromDisk(this)
    }
}