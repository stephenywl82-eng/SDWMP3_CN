package com.sdw.music.player

import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用语言管理：运行时切换 + 持久化。
 *
 * 用法：
 *  - AppLanguageManager.getCurrent(context): 返回 "zh" / "en" / "" (跟随系统)
 *  - AppLanguageManager.set(context, "en"): 切换语言（会触发 Activity 重建）
 *
 * 后续新增语言：只需在 [SUPPORTED] 加一个 code，并在 res/values-<code>/strings.xml 放翻译。
 */
object AppLanguageManager {

    private const val PREFS = "app_language_prefs"
    private const val KEY_LANG = "app_language"

    /** 支持的语言 code（与 res/values-<code> 目录名对应）；"" 表示跟随系统 */
    val SUPPORTED = listOf("", "zh", "en", "es", "ja", "ko", "fr", "zh-TW", "pt", "hi")

    fun getCurrent(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "") ?: ""
    }

    /**
     * 切换语言并持久化。会触发系统重建 Activity，
     * Compose 的 stringResource() 自动读取新语言。
     */
    fun set(context: Context, code: String) {
        val normalized = if (code in SUPPORTED) code else ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, normalized).apply()
        apply(context, normalized)
    }

    /** 在 Application.onCreate 调用一次，恢复上次选择的语言。 */
    fun applyStored(context: Context) {
        apply(context, getCurrent(context))
    }

    private fun apply(context: Context, code: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：用系统 LocaleManager（需 manifest 声明 localeConfig）
            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
            val locales = if (code.isEmpty()) {
                android.os.LocaleList.getEmptyLocaleList()
            } else {
                android.os.LocaleList.forLanguageTags(code)
            }
            localeManager.applicationLocales = locales
        } else {
            // Android 12 及以下：用 AppCompatDelegate
            val locales = if (code.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(code)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
