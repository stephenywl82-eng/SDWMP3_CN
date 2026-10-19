package com.sdw.music.player
import com.sdw.music.player.core.audio.dsp.AutoEqPresetStore
import com.sdw.music.player.core.audio.dsp.LoudnessCache
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.metadata.TagGainCache

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class SDWApp : Application() {
    // [V8.214] 后台作用域：把磁盘 I/O 类初始化（调色板缓存、内置音效包拷贝）移出主线程，
    // 配合 SongRepository / AutoEq 的异步加载，消除开屏主线程阻塞导致的「没有响应」。
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // [V8.130] 全局未捕获异常处理器：最前面安装。
        // 此前 CrashHandler 写好了却从未 install（全工程 0 调用点），凡是崩溃都是黑盒；
        // 挂上后崩一次即写 filesDir/music_pro_crash.log，可 adb run-as 拉取定位。
        runCatching { CrashHandler.install(this) }
            .onFailure { android.util.Log.e("SDWApp", "CrashHandler install failed", it) }
        // 恢复用户选择的语言（必须在任何 UI 初始化前）
        runCatching { AppLanguageManager.applyStored(this) }
        // 恢复用户选择的主题（浅色/深色）
        runCatching { ThemeManager.init(this) }
        // 恢复自定义壁纸设置（背景/模糊/压暗/取色感知）
        runCatching { WallpaperManager.init(this) }
        // Initialize SongRepository with application context（内部已改为后台加载）
        runCatching { SongRepository.init(this) }
        // Load persisted palette cache from disk（移出主线程）
        bgScope.launch { runCatching { MemoryManager.loadPaletteFromDisk(this@SDWApp) } }
        // 加载 AutoEQ 全量耳机预设库（assets/autoeq_presets.bin，6033 款；内部已改为后台加载）
        runCatching { AutoEqPresetStore.init(this) }
        // 【V8.424】过渡采样音效已删除：不再拷贝内置音效包。
        // 【V8.233】响度扫描缓存：设置页可能在 MusicService 起来之前就读它
        bgScope.launch {
            runCatching { com.sdw.music.player.core.audio.dsp.LoudnessCache.init(this@SDWApp) }
            runCatching { com.sdw.music.player.core.audio.metadata.TagGainCache.init(this@SDWApp) }
        }
    }
}