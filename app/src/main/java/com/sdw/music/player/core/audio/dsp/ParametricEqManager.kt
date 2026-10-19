package com.sdw.music.player.core.audio.dsp
import com.sdw.music.player.core.audio.dsp.AutoEqPresetManager
import com.sdw.music.player.core.audio.dsp.EqualizerManager
import com.sdw.music.player.core.audio.playback.MusicService
import com.sdw.music.player.core.audio.engine.OboeDirectPlayer
import com.sdw.music.player.core.audio.engine.UsbDacManager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 【V9.20】手动参数 EQ —— 对标发烧级 / Poweramp 参数 EQ。
 *
 * - 频段数可选 10 / 15 / 30（数据模型与 native 均支持到 64，对标 Poweramp 参数 EQ）。
 * - 每段独立可调：类型(PK/LS/HS) / 中心频率 / 增益 / Q。
 * - 经现有 AutoEQ 通道下发：
 *     Oboe  → OboeDirectPlayer.setAutoEq10Band
 *     DAC   → UsbDacManager.setAutoEq10Band（经 MusicService.applyAutoEq）
 *
 * ⚠️ native 端 AutoEQ biquad 银行已扩到 64 段（三期）；数据模型 MAX_BANDS=64；
 *    第 11~30 段需扩展 oboe_bridge.cpp / usb_audio 的 biquad 组（见 ParametricEqScreen 内说明），
 *    本管理器已按 10 段封顶下发，多余段在 UI 以提示条标注「待 native 扩展」。
 */
object ParametricEqManager {
    private const val TAG = "ParametricEqManager"
    const val MAX_BANDS = 64
    const val DEFAULT_BANDS = 30   // 二期：UI 默认 30 段，对标 Poweramp 30 段图形档（仍可在界面切 10/15/30）
    const val NATIVE_MAX_BANDS = 64   // native AutoEQ biquad 银行上限（二期：Oboe/DAC 银行已扩到 30）

    const val TYPE_PEAKING = 0
    const val TYPE_HIGH_SHELF = 1
    const val TYPE_LOW_SHELF = 2

    data class Band(
        val type: Int = TYPE_PEAKING,
        val freq: Float = 1000f,
        val gain: Float = 0f,        // dB, ±15
        val q: Float = 1.0f,         // 0.1 .. 18
        val enabled: Boolean = true
    ) {
        fun isActive(): Boolean = enabled && kotlin.math.abs(gain) > 0.001f
    }

    data class Preset(
        val name: String = "Custom PEQ",
        val preamp: Float = 0f,      // dB（通常为负，防削波）
        val bands: List<Band> = defaultBands(DEFAULT_BANDS)
    )

    private const val PREFS = "peq_v1"
    private const val KEY_NAME = "name"
    private const val KEY_PREAMP = "preamp"
    private const val KEY_COUNT = "count"
    private const val K_T = "t_"   // type
    private const val K_F = "f_"   // freq
    private const val K_G = "g_"   // gain
    private const val K_Q = "q_"   // q
    private const val K_E = "e_"   // enabled
    private const val KEY_ENABLED = "enabled"   // PEQ 主开关（持久化，供重建后恢复）

    private val _enabled = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 对数分布的默认频段中心频率（20Hz ~ 20kHz）。 */
    fun defaultBands(count: Int): List<Band> {
        val n = count.coerceIn(1, MAX_BANDS)
        val logMin = kotlin.math.ln(20.0)
        val logMax = kotlin.math.ln(20000.0)
        return List(n) { i ->
            val t = if (n == 1) 0.5 else i.toDouble() / (n - 1)
            val f = kotlin.math.exp(logMin + t * (logMax - logMin)).toFloat()
            Band(freq = f, gain = 0f, q = 1.0f, type = TYPE_PEAKING, enabled = true)
        }
    }

    fun load(context: Context): Preset {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = p.getInt(KEY_COUNT, DEFAULT_BANDS).coerceIn(1, MAX_BANDS)
        val defaults = defaultBands(count)
        val bands = List(count) { i ->
            Band(
                type = p.getInt(K_T + i, TYPE_PEAKING).coerceIn(0, 2),
                freq = p.getFloat(K_F + i, defaults[i].freq).coerceIn(20f, 20000f),
                gain = p.getFloat(K_G + i, 0f),
                q = p.getFloat(K_Q + i, 1.0f).coerceIn(0.1f, 18f),
                enabled = p.getBoolean(K_E + i, true)
            )
        }
        return Preset(
            name = p.getString(KEY_NAME, "Custom PEQ") ?: "Custom PEQ",
            preamp = p.getFloat(KEY_PREAMP, 0f),
            bands = bands
        )
    }

    fun save(context: Context, preset: Preset) {
        val n = preset.bands.size.coerceIn(1, MAX_BANDS)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        p.putString(KEY_NAME, preset.name)
        p.putFloat(KEY_PREAMP, preset.preamp)
        p.putInt(KEY_COUNT, n)
        preset.bands.forEachIndexed { i, b ->
            p.putInt(K_T + i, b.type); p.putFloat(K_F + i, b.freq)
            p.putFloat(K_G + i, b.gain); p.putFloat(K_Q + i, b.q)
            p.putBoolean(K_E + i, b.enabled)
        }
        // 段数减少时清掉残留旧 key
        for (i in n until MAX_BANDS) {
            p.remove(K_T + i); p.remove(K_F + i); p.remove(K_G + i); p.remove(K_Q + i); p.remove(K_E + i)
        }
        p.apply()
    }

    /** PEQ 是否占用 AutoEQ native 通道（peq_v1 主开 = On 且有启用频段）。
     *  用于让 5 段 EQ 的「耳机 AutoEQ」在重发时不覆盖正在生效的 PEQ（两者共用同一条通道）。 */
    fun isActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return false
        return load(context).bands.any { it.isActive() }
    }

    fun isSupported(context: Context): Boolean {
        val svc = MusicService.instance ?: return false
        return svc.isDacActive() || EqualizerManager.isOboeMode(context)
    }

    fun activeCount(preset: Preset): Int = preset.bands.count { it.isActive() }

    /**
     * 下发手动参数 EQ。Oboe/DAC 经原生 NATIVE_MAX_BANDS(=64) 段 AutoEQ 通道，前 NATIVE_MAX_BANDS 个启用段生效。
     */
    /**
     * 下发手动参数 EQ。按频段原始索引下发：第 i 段 → native 第 i 个 biquad（不重排），
     * 这样「先选 10 段曲风模板、再调 11~64 段」时各段落在正确位置、互不挤占槽位。
     * Oboe/DAC 经原生 NATIVE_MAX_BANDS(=64) 段 AutoEQ 通道，全部 64 段均生效。
     */
    fun applyPreset(context: Context, preset: Preset) {
        val n = preset.bands.size.coerceAtMost(NATIVE_MAX_BANDS)
        val gains = FloatArray(NATIVE_MAX_BANDS); val freqs = FloatArray(NATIVE_MAX_BANDS)
        val qs = FloatArray(NATIVE_MAX_BANDS); val types = IntArray(NATIVE_MAX_BANDS)
        for (i in 0 until n) {
            val b = preset.bands[i]
            gains[i] = b.gain
            freqs[i] = b.freq.coerceIn(20f, 20000f)
            qs[i] = b.q.coerceIn(0.1f, 18f)
            types[i] = b.type
        }
        val hasActive = (0 until n).any { preset.bands[it].isActive() }
        val svc = MusicService.instance
        when {
            svc != null && svc.isDacActive() -> svc.applyAutoEq(gains, freqs, qs, types, preset.preamp)
            EqualizerManager.isOboeMode(context) -> {
                val oboe = svc?.oboeDirectPlayer ?: run {
                    Log.w(TAG, "applyPreset: OboeDirectPlayer unavailable")
                    return
                }
                // 全量下发（含平坦段）：native 按索引把第 i 段映射到第 i 个 biquad，保证槽位与段序一致。
                val filters = ArrayList<AutoEqFilter>(n)
                for (i in 0 until n) {
                    val b = preset.bands[i]
                    filters.add(AutoEqFilter(typeToStr(b.type), b.freq, b.gain, b.q))
                }
                val autoPreset = AutoEqPreset(preset.name, "Custom", "custom", preset.preamp, filters)
                AutoEqPresetManager.applyPreset(oboe, autoPreset)
            }
            else -> {
                Log.w(TAG, "applyPreset: output mode not supported (need Oboe/DAC)")
                return
            }
        }
        save(context, preset)
        _enabled.value = hasActive
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, hasActive).apply()
        // 同步主界面 DSP 主开关：参数 EQ 生效时，主界面「均衡器」开关也显示打开
        if (hasActive && !EqualizerManager.isEnabled()) {
            EqualizerManager.setEnabled(true, context)
        }
        Log.i(TAG, "Manual PEQ applied: $n bands (active=$hasActive), preamp=${preset.preamp}")
    }

    fun clear(context: Context) {
        EqualizerManager.clearAutoEq(context)   // 复用现有 AutoEQ 复位（DAC/Oboe 统一）
        EqualizerManager.setEnabled(false, context)   // 同步主界面 DSP 主开关为关闭
        _enabled.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false).apply()
        Log.i(TAG, "Manual PEQ cleared")
    }

    /**
     * 【V9.0x】播放器重建 / DAC 重开后按存储重发手动 PEQ。
     *
     * native biquad 银行在新 player / DAC 重开后整条丢失，仅刷新 enable 标志不足以保留效果，
     * 必须像 5 段 EQ（EqualizerManager.restoreSettings）一样把频段系数重新下发，否则会出现
     * 「界面里 PEQ 显示 On、但听不到效果」——与先前 5 段 EQ 重建失效（'开了预设没感觉'）同类问题。
     */
    fun restoreSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val preset = load(context)
        if (preset.bands.none { it.isActive() }) return
        applyPreset(context, preset)
        Log.i(TAG, "Manual PEQ restored on rebuild: ${preset.bands.count { it.isActive() }} active bands")
    }

    /**
     * 【V9.2x】设置页「DSP 引擎」子开关 / PEQ 界面统一入口：把参数 EQ 做成正规子开关。
     * 开 = 按存储预设下发（有启用频段才真生效），关 = 清空整链。
     * 同时把开关状态持久化到 settings.parametric_eq_enabled，供设置页子开关与 PEQ 界面双向同步。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("parametric_eq_enabled", enabled).apply()
        if (enabled) {
            val preset = load(context)
            if (preset.bands.any { it.isActive() }) {
                applyPreset(context, preset)
            } else {
                // 无启用频段：仅置状态为开（等用户在 PEQ 界面调整），不报错、无声音变化
                _enabled.value = true
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, true).apply()
            }
        } else {
            clear(context)
        }
        Log.i(TAG, "ParametricEq enabled=$enabled (settings.parametric_eq_enabled synced)")
    }

    private fun typeToStr(t: Int) = when (t) {
        TYPE_LOW_SHELF -> "LS"
        TYPE_HIGH_SHELF -> "HS"
        else -> "PK"
    }
}
