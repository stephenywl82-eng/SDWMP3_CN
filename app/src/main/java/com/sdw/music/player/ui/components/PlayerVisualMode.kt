package com.sdw.music.player.ui.components

import android.content.SharedPreferences

/**
 * V8.439：进度条上方的可视化内容，四选一。
 *
 * ## 为什么要有这个
 * 用户看到的是进度条上方的 **VU 表**（VuMeter），而 djay 式的红/绿/蓝着色整曲波形
 * （FullSongWaveform，带进拍/出拍横线）一直写在 `DjLandscapeSurface.kt` 里但
 * **没有任何调用点**——V8.380 把 DJ 独立界面上线时，用户反馈"太复杂"，
 * 整块下线了，于是波形和 VU 表变成二选一但用户没法选。
 *
 * 这里把两者放进同一个设置项，让用户能对比着看（"都显示"模式最有用：
 * 横线位置准不准、波形和 VU 的响度是否同步，一眼就能判断）。
 *
 * ## 迁移
 * 老版本只有 `vu_meter_enabled` 一个布尔。首次读取时按它推导：
 * true -> VU，false -> NONE。推导后立刻写入新 key，之后只读新 key。
 */
enum class PlayerVisualMode(val key: Int) {
    /** 只显示 VU 表（老行为，默认） */
    VU(0),
    /** 只显示整曲波形（djay 着色 + 进拍/出拍横线） */
    WAVEFORM(1),
    /** 两个都显示：VU 在上、波形在下 */
    BOTH(2),
    /** 都不显示 */
    NONE(3);

    // 【V8.485】用户要求彻底移除波形显示：波形模式一律不渲染，旧 WAVEFORM/BOTH 回落为 VU。
    val showVu: Boolean get() = this != NONE
    val showWave: Boolean get() = false

    companion object {
        const val PREF_KEY = "player_visual_mode"
        private const val LEGACY_VU_KEY = "vu_meter_enabled"

        fun fromKey(k: Int): PlayerVisualMode =
            // 【V8.485】WAVEFORM(1)/BOTH(2) 已删除，统一回落到 VU；其余原样。
            when (k) { 1, 2 -> VU; else -> entries.firstOrNull { it.key == k } ?: VU }

        /**
         * 读当前模式，含老 pref 迁移。**只读不写**——
         * 迁移写回交给调用方（PlayerScreen 每帧会调这里，不能在这里做 edit()）。
         */
        fun read(prefs: SharedPreferences): PlayerVisualMode {
            if (prefs.contains(PREF_KEY)) {
                return fromKey(prefs.getInt(PREF_KEY, VU.key))
            }
            // 迁移：老 pref 默认 true（= 显示 VU 表）
            return if (prefs.getBoolean(LEGACY_VU_KEY, true)) VU else NONE
        }

        /** 写入新 key，并同步老 key（保持向后兼容，别的地方可能还在读它）。 */
        fun write(prefs: SharedPreferences, mode: PlayerVisualMode) {
            prefs.edit()
                .putInt(PREF_KEY, mode.key)
                .putBoolean(LEGACY_VU_KEY, mode.showVu)
                .apply()
        }
    }
}
