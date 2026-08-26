package com.sdw.music.player

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * 自定义壁纸管理：全局背景壁纸（作用于列表/设置类界面，播放器豁免）。
 *
 * 功能：
 *  - 从系统相册选图 → 拷贝到 filesDir/wallpaper 持久化
 *  - 模糊度 / 压暗度 两个可调参数（不同壁纸自己调）
 *  - 取色感知：Palette 测壁纸亮度，动态决定 scrim 强度与文字深浅
 *
 * 用法同 ThemeManager：init() 在 Application.onCreate 调用一次，Compose 里读 State 自动重组。
 */
object WallpaperManager {

    private const val PREFS = "wallpaper_prefs"
    private const val KEY_PATH = "wallpaper_path"
    private const val KEY_BLUR = "wallpaper_blur"
    private const val KEY_SCRIM = "wallpaper_scrim"
    private const val KEY_COLOR_AWARE = "wallpaper_color_aware"

    /** 壁纸文件绝对路径（filesDir 下），null = 未设置 */
    private val _wallpaperPath = mutableStateOf<String?>(null)

    /** 模糊半径（dp），范围 0..40，默认 22 */
    private val _blurAmount = mutableStateOf(22f)

    /** 压暗 scrim 强度（0..0.85），默认 0.40 */
    private val _scrimAmount = mutableStateOf(0.40f)

    /** 取色感知开关（默认开） */
    private val _colorAwareEnabled = mutableStateOf(true)

    /** 壁纸亮度（0..1，Palette 异步算出，0.5 为中性灰），用于文字深浅与 scrim 自适应 */
    private val _wallpaperBrightness = mutableStateOf(0.5f)

    val wallpaperPath: State<String?> = _wallpaperPath
    val blurAmount: State<Float> = _blurAmount
    val scrimAmount: State<Float> = _scrimAmount
    val colorAwareEnabled: State<Boolean> = _colorAwareEnabled
    val wallpaperBrightness: State<Float> = _wallpaperBrightness

    /** 是否已设置壁纸 */
    fun hasWallpaper(): Boolean = _wallpaperPath.value?.let { File(it).exists() } == true

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _wallpaperPath.value = prefs.getString(KEY_PATH, null)
        _blurAmount.value = prefs.getFloat(KEY_BLUR, 22f)
        _scrimAmount.value = prefs.getFloat(KEY_SCRIM, 0.40f)
        _colorAwareEnabled.value = prefs.getBoolean(KEY_COLOR_AWARE, true)
        // 文件被清理则复位
        if (_wallpaperPath.value != null && !File(_wallpaperPath.value!!).exists()) {
            _wallpaperPath.value = null
            prefs.edit().remove(KEY_PATH).apply()
        }
    }

    /** 从 uri 拷贝图片到 filesDir，持久化路径并触发重组 */
    fun setWallpaper(context: Context, uri: android.net.Uri) {
        try {
            val dir = File(context.filesDir, "wallpaper")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, "wallpaper_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.exists() && dest.length() > 0) {
                _wallpaperPath.value = dest.absolutePath
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PATH, dest.absolutePath).apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("WallpaperManager", "setWallpaper failed: ${e.message}", e)
        }
    }

    fun clearWallpaper(context: Context) {
        _wallpaperPath.value?.let { p -> runCatching { File(p).delete() } }
        _wallpaperPath.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PATH).apply()
    }

    fun setBlur(context: Context, value: Float) {
        _blurAmount.value = value.coerceIn(0f, 40f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BLUR, _blurAmount.value).apply()
    }

    fun setScrim(context: Context, value: Float) {
        _scrimAmount.value = value.coerceIn(0f, 0.85f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SCRIM, _scrimAmount.value).apply()
    }

    fun setColorAware(context: Context, enabled: Boolean) {
        _colorAwareEnabled.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COLOR_AWARE, enabled).apply()
    }

    /** Palette 分析结果回写（由 WallpaperBackground 组件异步调用） */
    fun updateBrightness(brightness: Float) {
        _wallpaperBrightness.value = brightness.coerceIn(0f, 1f)
    }

    /**
     * 取色感知开启 + 壁纸偏亮时，文字应用深色。
     * 阈值 0.62：壁纸明显偏亮（浅色/花哨亮背景）才切换，避免频繁闪烁。
     */
    fun shouldUseDarkText(): Boolean {
        return _colorAwareEnabled.value && _wallpaperBrightness.value >= 0.62f
    }
}
