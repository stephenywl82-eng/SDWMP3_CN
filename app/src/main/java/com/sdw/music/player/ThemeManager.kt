package com.sdw.music.player

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * 主题管理：浅色 / 深色模式运行时切换 + 持久化。
 *
 * 用法：
 *  - ThemeManager.init(context): 在 Application.onCreate 调用一次，恢复上次选择
 *  - ThemeManager.lightMode.value: 在 Compose 中读取（自动触发重组）
 *  - ThemeManager.setLightMode(context, enabled): 切换并持久化
 */
object ThemeManager {

    private const val PREFS = "theme_prefs"
    private const val KEY_LIGHT = "light_mode"
    private const val KEY_FOLLOW = "follow_system"

    private val _lightMode = mutableStateOf(false)

    /** 【V8.111】 是否跟随系统深/浅色。开启后 lightMode 被忽略，改读系统 uiMode。 */
    private val _followSystem = mutableStateOf(false)

    /** 当前是否为浅色模式（Compose State，读取会自动订阅重组） */
    val lightMode: State<Boolean> = _lightMode

    /** 【V8.111】 是否跟随系统主题（Compose State） */
    val followSystem: State<Boolean> = _followSystem

    /** 在 Application.onCreate 调用，恢复持久化的主题选择 */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _lightMode.value = p.getBoolean(KEY_LIGHT, false)
        _followSystem.value = p.getBoolean(KEY_FOLLOW, false)
    }

    /** 切换浅色/深色模式并持久化，立即触发全局重组 */
    fun setLightMode(context: Context, enabled: Boolean) {
        _lightMode.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LIGHT, enabled).apply()
    }

    /** 【V8.111】 切换「跟随系统主题」并持久化，立即触发全局重组 */
    fun setFollowSystem(context: Context, enabled: Boolean) {
        _followSystem.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FOLLOW, enabled).apply()
    }
}
