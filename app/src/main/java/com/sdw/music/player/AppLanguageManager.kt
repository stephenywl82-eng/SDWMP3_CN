package com.sdw.music.player

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.Locale

/**
 * 应用语言管理：运行时切换 + 持久化。
 *
 * 用法：
 *  - AppLanguageManager.getCurrent(context): 返回 "zh" / "en" / "" (跟随系统)
 *  - AppLanguageManager.set(context, "en"): 切换语言（会触发界面重建）
 *
 * V9.20q 实现说明（重要）：
 * 本工程没有 AppCompat 基础设施——MainActivity 是 ComponentActivity、主题是
 * 平台 Theme.DeviceDefault。旧实现走 AppCompatDelegate.setApplicationLocales()，
 * 该 API 依赖 AppCompatActivity / AppCompat 初始化过的 Context 才能生效，
 * 在本工程上只会静默失败（异常被 try-catch 吞掉，日志见 "apply language failed"），
 * 表现为「设置里选了语言、偏好也写进去了，界面却不变」。
 *
 * 现在用两个互补机制，不依赖 AppCompat：
 *  1) API 33+：平台 LocaleManager.setApplicationLocales —— 系统级每应用语言，
 *     系统会自动重建 Activity，系统设置里的「应用语言」也同步可见。
 *  2) 全版本：MainActivity.attachBaseContext 调 [wrapContext] 用持久化的语言
 *     包裹基础 Context，冷启动与手动 recreate() 都读到正确语言的资源。
 * 偏好存储（SharedPreferences）仍是唯一真源；系统语言在冷启动时若与偏好不一致
 * （用户在系统设置里改过每应用语言），以系统为准并回写偏好。
 *
 * 后续新增语言：只需在 [SUPPORTED] 加一个 code，并在 res/values-<code>/strings.xml 放翻译。
 */
object AppLanguageManager {

    private const val PREFS = "app_language_prefs"
    private const val KEY_LANG = "app_language"
    private const val TAG = "AppLanguageManager"

    /** 支持的语言 code（与 res/values-<code> 目录名对应）；"" 表示跟随系统 */
    val SUPPORTED = listOf("", "zh", "en", "es", "ja", "ko", "fr", "zh-TW", "pt", "hi")

    fun getCurrent(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "") ?: ""
    }

    /**
     * 切换语言并持久化。
     * API 33+ 由系统 LocaleManager 触发 Activity 自动重建；
     * 更低版本手动 recreate() 当前 Activity（attachBaseContext 会重新读取语言）。
     */
    fun set(context: Context, code: String) {
        val normalized = if (code in SUPPORTED) code else ""
        persist(context, normalized)
        apply(context, normalized)
        if (Build.VERSION.SDK_INT < 33) {
            findActivity(context)?.recreate()
        }
    }

    /** 在 Application.onCreate 调用一次，恢复上次选择的语言。 */
    fun applyStored(context: Context) {
        var code = getCurrent(context)
        // 用户在系统设置里改过「每应用语言」时以系统为准，回写偏好。
        if (Build.VERSION.SDK_INT >= 33) {
            val sysTag = systemLocaleTag(context)
            if (sysTag != null) {
                val mapped = mapTag(sysTag)
                if (mapped in SUPPORTED && mapped != code) {
                    code = mapped
                    persist(context, code)
                }
            }
        }
        apply(context, code)
    }

    /**
     * 供 MainActivity.attachBaseContext 使用：按当前生效语言包裹基础 Context。
     * 生效优先级：系统每应用语言（API 33+，非空时）> 应用内偏好 > 系统默认。
     */
    fun wrapContext(base: Context): Context {
        val code = if (Build.VERSION.SDK_INT >= 33) {
            val sysTag = systemLocaleTag(base)
            if (sysTag != null) mapTag(sysTag).takeIf { it in SUPPORTED } ?: getCurrent(base)
            else getCurrent(base)
        } else {
            getCurrent(base)
        }
        if (code.isEmpty()) return base
        val locale = Locale.forLanguageTag(code)
        val cfg = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(cfg)
    }

    // ---- internals ----

    private fun persist(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, code).apply()
    }

    private fun apply(context: Context, code: String) {
        if (Build.VERSION.SDK_INT < 33) return  // 低版本靠 wrapContext + recreate
        runCatching {
            val lm = context.getSystemService(LocaleManager::class.java)
            lm.applicationLocales = if (code.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(code)
            }
        }.onFailure {
            Log.e(TAG, "apply language failed: ${it.message}", it)
        }
    }

    /** 系统每应用语言的第一个 tag；系统未设置（返回空列表）时返回 null。 */
    private fun systemLocaleTag(context: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching {
            val tags = context.getSystemService(LocaleManager::class.java)
                .applicationLocales.toLanguageTags()
            if (tags.isEmpty()) null else tags.split(',').first()
        }.getOrNull()
    }

    /** 把系统返回的 BCP47 tag 归一到我们的 code（zh-Hans->zh、zh-Hant-TW->zh-TW）。 */
    private fun mapTag(tag: String): String = when (tag) {
        "zh-Hans", "zh-Hans-CN", "zh-CN" -> "zh"
        "zh-Hant-TW" -> "zh-TW"
        else -> tag
    }

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
