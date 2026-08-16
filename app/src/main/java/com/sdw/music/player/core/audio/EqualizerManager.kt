package com.sdw.music.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import com.sdw.music.player.core.audio.AutoEqPresetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 【v5.39】离线 EQ 预设管理器
 * 【V7.xx 重构】移除所有 Sink EQ 代码。
 *   Oboe 独占模式 → 只使用 DSP（由 OboeDirectPlayer 管理），不用 EQ
 *   非 Oboe 模式   → 只使用 Android Equalizer 28 种预设
 */
object EqualizerManager {

    private const val TAG = "EqualizerManager"
    private const val PREFS_NAME = "equalizer_prefs"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_AUTO_EQ_PRESET = "auto_eq_preset"

    private var equalizer: Equalizer? = null
    private var audioSessionId: Int = 0

    /** 供 Compose 观察的初始化状态 */
    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    /** 供 Compose 观察的启用状态 */
    private val _enabled = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 检查当前是否为 Oboe 独占模式（EQ Unavailable，应使用 DSP） */
    fun isOboeMode(context: Context): Boolean {
        val mode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("audio_output", "AAudio (Direct)") ?: "AAudio (Direct)"
        // Must match MusicService.isOboeDirectMode(): native lib load failure → AudioTrack → Android EQ available
        return (mode == "AAudio (Direct)" || mode == "Oboe Exclusive" || mode == "Oboe独占") && OboeDirectPlayer.nativeLibLoaded
    }

    /** EQ 预设定义 */
    data class EqPreset(
        val id: String,
        val name: String,
        val description: String,
        val bands: ShortArray  // 5 段 EQ 增益值(单位:millibels,范围 -1500 ~ +1500)
    )

    /** 预设列表 */
    val PRESETS = listOf(
        // ========== Flat/通用预设 ==========
        EqPreset(
            id = "flat", name = "Flat", description = "No correction, original sound",
            bands = shortArrayOf(0, 0, 0, 0, 0)
        ),
        // ========== Steven 专属 ==========
        EqPreset(
            id = "steven_special", name = "Steven Special",
            description = "Optimized for treble harshness, smooth roll-off above 8kHz",
            bands = shortArrayOf(0, 0, -100, -400, -900)
        ),
        EqPreset(
            id = "bass_boost", name = "Bass Boost", description = "Powerful low-frequency boost",
            bands = shortArrayOf(1000, 500, 0, -200, -400)
        ),
        EqPreset(
            id = "vocal", name = "Vocal Boost", description = "Mid-frequency boost, clear vocals",
            bands = shortArrayOf(-200, 400, 800, 400, -200)
        ),
        EqPreset(
            id = "treble_boost", name = "Treble Boost", description = "Treble boost, clear detail",
            bands = shortArrayOf(-300, -100, 200, 500, 800)
        ),
        EqPreset(
            id = "rock", name = "Rock", description = "V-shaped curve, boosted lows and highs",
            bands = shortArrayOf(600, -200, -400, -200, 600)
        ),
        EqPreset(
            id = "electronic", name = "Electronic", description = "For Electronic music, strong low end",
            bands = shortArrayOf(800, 400, -200, 0, 400)
        ),
        EqPreset(
            id = "jazz", name = "Jazz", description = "Warm and detailed, boosted mids and highs",
            bands = shortArrayOf(200, 300, 400, 500, 300)
        ),
        EqPreset(
            id = "classical", name = "Classical", description = "Wide dynamics, prominent high-mids",
            bands = shortArrayOf(300, 200, 0, 400, 500)
        ),
        // ========== Bose 品牌 ==========
        EqPreset(
            id = "bose_warm", name = "Bose Warm", description = "Bose signature, boosted lows, warm vocals",
            bands = shortArrayOf(600, 300, 0, -200, -300)
        ),
        EqPreset(
            id = "bose_vibrant", name = "Bose Bright", description = "Bose style, treble boost, transparent",
            bands = shortArrayOf(400, 200, 0, 300, 500)
        ),
        // ========== Sony 品牌 ==========
        EqPreset(
            id = "sony_bright", name = "Sony Bright", description = "Sony signature, bright highs, resolution",
            bands = shortArrayOf(200, 100, 200, 400, 600)
        ),
        EqPreset(
            id = "sony_bass", name = "Sony Bass Boost", description = "Sony Extra Bass style, strong low end",
            bands = shortArrayOf(800, 600, 200, -100, -200)
        ),
        EqPreset(
            id = "sony_balanced", name = "Sony Balanced", description = "Sony Balanced style, balanced three-band",
            bands = shortArrayOf(200, 100, 100, 100, 200)
        ),
        // ========== Sennheiser 品牌 ==========
        EqPreset(
            id = "sennheiser_warm", name = "Sennheiser Warm", description = "Sennheiser style, warm, natural, full vocals",
            bands = shortArrayOf(300, 400, 300, 100, 0)
        ),
        EqPreset(
            id = "sennheiser_analytical", name = "Sennheiser Analytical", description = "Sennheiser analytical style, rich detail",
            bands = shortArrayOf(-100, 0, 200, 400, 300)
        ),
        // ========== JBL 品牌 ==========
        EqPreset(
            id = "jbl_pure_bass", name = "JBL Pure Bass", description = "JBL Signature Bass, powerful",
            bands = shortArrayOf(900, 700, 100, -200, -300)
        ),
        EqPreset(
            id = "jbl_club", name = "JBL Club", description = "JBL Club style, for Pop/Electronic",
            bands = shortArrayOf(700, 500, 0, 200, 400)
        ),
        // ========== AKG 品牌 ==========
        EqPreset(
            id = "akg_studio", name = "AKG Studio", description = "AKG Studio style, balanced, precise",
            bands = shortArrayOf(100, 200, 300, 200, 100)
        ),
        EqPreset(
            id = "akg_reference", name = "AKG Reference", description = "AKG Reference tuning, neutral, natural",
            bands = shortArrayOf(0, 100, 100, 100, 0)
        ),
        // ========== Apple 品牌 ==========
        EqPreset(
            id = "airpods_neutral", name = "AirPods Neutral", description = "Apple signature, neutral, balanced",
            bands = shortArrayOf(100, 100, 100, 100, 100)
        ),
        EqPreset(
            id = "airpods_spatial", name = "AirPods Spatial", description = "Simulated spatial audio, wide soundstage",
            bands = shortArrayOf(200, 0, -100, 0, 200)
        ),
        // ========== Samsung 品牌 ==========
        EqPreset(
            id = "samsung_dynamic", name = "Samsung Dynamic", description = "Samsung signature, strong dynamics",
            bands = shortArrayOf(400, 200, 0, 200, 400)
        ),
        EqPreset(
            id = "samsung_clear", name = "Samsung Clear", description = "Samsung Clear style, vocal boost",
            bands = shortArrayOf(100, 300, 500, 300, 100)
        ),
        // ========== Audio-Technica 品牌 ==========
        EqPreset(
            id = "ath_monitor", name = "Audio-Technica Monitor", description = "Audio-Technica monitor style, accurate",
            bands = shortArrayOf(0, 100, 200, 100, 0)
        ),
        EqPreset(
            id = "ath_smooth", name = "Audio-Technica Smooth", description = "Audio-Technica smooth style, warm",
            bands = shortArrayOf(200, 300, 200, 100, 0)
        ),
        // ========== Beats 品牌 ==========
        EqPreset(
            id = "beats_bass", name = "Beats Bass Boost", description = "Beats signature bass boost, strong rhythm",
            bands = shortArrayOf(1000, 800, 200, -100, -200)
        ),
        EqPreset(
            id = "beats_pop", name = "Beats Pop", description = "Beats Pop style, for Pop music",
            bands = shortArrayOf(600, 400, 100, 200, 300)
        ),
        // ========== Shure 品牌 ==========
        EqPreset(
            id = "shure_monitor", name = "Shure Monitor", description = "Shure pro monitor style, precise",
            bands = shortArrayOf(0, 0, 100, 0, 0)
        ),
        // ========== Marshall 品牌 ==========
        EqPreset(
            id = "marshall_rock", name = "Marshall Rock", description = "Marshall Rock style, prominent guitar",
            bands = shortArrayOf(400, 100, -100, 300, 500)
        )
    )

    /**
     * 初始化 Android Equalizer
     * @param sessionId 音频会话 ID（从 ExoPlayer AudioTrack 获取）
     */
    fun init(sessionId: Int) {
        audioSessionId = sessionId

        if (sessionId == 0) {
            Log.w(TAG, "sessionId=0, waiting for valid sessionId from onAudioSessionIdChanged")
            return
        }

        try {
            // 释放旧的
            release()

            // 创建新的 Android Equalizer
            equalizer = Equalizer(0, sessionId).apply {
                enabled = false
            }
            _initialized.value = true

            Log.i(TAG, "Android Equalizer initialized: session=$sessionId, bands=${equalizer?.numberOfBands}")

        } catch (e: Exception) {
            Log.e(TAG, "Equalizer init failed: ${e.message}. Is AudioTrack active?")
            _initialized.value = false
        }
    }

    /**
     * 应用 EQ 预设（自动适配 Android Equalizer / Oboe DSP）
     */
    fun applyPreset(presetId: String, context: Context) {
        // 【V7.80】Oboe 模式：通过 DSP 5 段Equalizer应用预设
        if (isOboeMode(context)) {
            val preset = PRESETS.find { it.id == presetId } ?: return
            val oboe = MusicService.instance?.oboeDirectPlayer
            if (oboe == null) {
                Log.w(TAG, "applyPreset: OboeDirectPlayer not available, saving preference only")
                saveSettings(context, presetId != "flat", presetId)
                _enabled.value = presetId != "flat"
                return
            }

            if (presetId == "flat") {
                oboe.resetDspEq5Band()
                saveSettings(context, false, presetId)
                _enabled.value = false
                Log.d(TAG, "Oboe DSP: EQ cleared (flat)")
                return
            }

            // millibels → dB (1 dB = 100 millibels)
            val gainsDb = FloatArray(5) { i -> preset.bands[i].toFloat() / 100f }

            oboe.setDspEq5Band(gainsDb)
            saveSettings(context, true, presetId)
            _enabled.value = true
            Log.i(TAG, "Oboe DSP: Applied EQ preset '${preset.name}' gains=${gainsDb.contentToString()}")
            return
        }

        // 以下为 Android Equalizer 路径
        val eq = equalizer
        if (eq == null) {
            Log.w(TAG, "applyPreset: no Android Equalizer yet, saving preference only")
            // Save偏好，等 EQ 初始化后 restoreSettings 会自动应用
            saveSettings(context, presetId != "flat", presetId)
            _enabled.value = presetId != "flat"
            return
        }

        val preset = PRESETS.find { it.id == presetId } ?: return

        try {
            if (presetId == "flat") {
                eq.enabled = false
                saveSettings(context, false, presetId)
                _enabled.value = false
                Log.d(TAG, "EQ disabled (flat)")
                return
            }

            // 总增益限幅防止 Clipping
            val totalBoost = preset.bands.sum()
            val scaleFactor = if (totalBoost > 2000) {
                (2000 + (totalBoost - 2000) * 0.3f) / totalBoost
            } else {
                1f
            }

            val numBands = eq.numberOfBands.toInt()
            preset.bands.forEachIndexed { index, gain ->
                if (index < numBands) {
                    val scaledGain = (gain * scaleFactor).toInt().toShort()
                    eq.setBandLevel(index.toShort(), scaledGain)
                }
            }

            eq.enabled = true
            saveSettings(context, true, presetId)
            _enabled.value = true

            Log.i(TAG, "Applied EQ preset: ${preset.name}")

        } catch (e: Exception) {
            Log.e(TAG, "applyPreset failed: ${e.message}")
        }
    }

    /**
     * 应用 AutoEQ Headphone Correction预设（10 段 Parametric EQ）
     * @param presetName AutoEqPreset.name
     * @return true 成功, false 不适用（非 Oboe 或无匹配预设）
     */
    fun applyAutoEqPreset(presetName: String, context: Context): Boolean {
        if (!isOboeMode(context)) {
            Log.w(TAG, "AutoEQ only supported in Oboe mode")
            return false
        }
        val oboe = MusicService.instance?.oboeDirectPlayer ?: return false
        val preset = AutoEqPresetManager.findByName(presetName) ?: run {
            Log.w(TAG, "AutoEQ preset not found: $presetName")
            return false
        }
        val ok = AutoEqPresetManager.applyPreset(oboe, preset)
        if (ok) {
            saveAutoEqSettings(context, presetName)
            _enabled.value = true
        }
        return ok
    }

    /** 清除 AutoEQ，恢复默认 DSP */
    fun clearAutoEq(context: Context) {
        val oboe = MusicService.instance?.oboeDirectPlayer ?: return
        AutoEqPresetManager.clearPreset(oboe)
        saveAutoEqSettings(context, null)
        _enabled.value = false
        Log.i(TAG, "AutoEQ cleared")
    }

    /** 获取当前 AutoEQ 预设名（null=未激活） */
    fun getAutoEqPreset(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUTO_EQ_PRESET, null)
    }

    private fun saveAutoEqSettings(context: Context, presetName: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_AUTO_EQ_PRESET, presetName)
            .apply()
    }

    /**
     * 启用/禁用 EQ（自动适配 Android / Oboe）
     */
    fun setEnabled(enabled: Boolean, context: Context) {
        // 【V7.80】Oboe 模式：控制 DSP
        if (isOboeMode(context)) {
            val oboe = MusicService.instance?.oboeDirectPlayer
            if (oboe != null) {
                oboe.setDspEnabled(enabled)
                if (!enabled) oboe.resetDspEq5Band()
                saveSettings(context, enabled, getCurrentPresetId(context))
                _enabled.value = enabled
                Log.d(TAG, "Oboe DSP enabled=$enabled")
            }
            return
        }
        // Android Equalizer 路径
        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "setEnabled failed: ${e.message}")
        }
        saveSettings(context, enabled, getCurrentPresetId(context))
        _enabled.value = enabled
        Log.d(TAG, "EQ enabled=$enabled")
    }

    /**
     * 释放 Android Equalizer 资源
     */
    fun release() {
        try {
            equalizer?.release()
        } catch (_: Exception) {}
        equalizer = null
        _initialized.value = false
        _enabled.value = false
        Log.d(TAG, "Equalizer released")
    }

    fun isInitialized(): Boolean {
        // 【V7.80】Oboe 模式下 DSP 始终可用
        if (MusicService.instance?.oboeDirectPlayer != null) return true
        return equalizer != null
    }
    fun isEnabled(): Boolean = _enabled.value

    fun getCurrentPresetId(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PRESET, "flat") ?: "flat"
    }

    fun getCurrentPreset(context: Context): EqPreset {
        val id = getCurrentPresetId(context)
        return PRESETS.find { it.id == id } ?: PRESETS[0]
    }

    fun getAvailablePresets(): List<String> = PRESETS.map { it.name }

    fun getCurrentPresetName(): String {
        val context = MusicService.instance ?: return "Flat"
        val id = getCurrentPresetId(context)
        return (PRESETS.find { it.id == id } ?: PRESETS[0]).name
    }

    fun applyPreset(presetName: String) {
        val context = MusicService.instance ?: return
        val preset = PRESETS.find { it.name == presetName } ?: return
        applyPreset(preset.id, context)
    }

    private fun saveSettings(context: Context, enabled: Boolean, presetId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_PRESET, presetId)
            .apply()
    }

    fun restoreSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val presetId = prefs.getString(KEY_PRESET, "flat") ?: "flat"

        if (enabled && presetId != "flat") {
            applyPreset(presetId, context)
        } else {
            _enabled.value = enabled
            // 【V7.80】Oboe 模式: Settings _enabled 为 false 时清除预设
            // Skip reset if MSEB is active — MSEB manages 5-band EQ independently
            if (isOboeMode(context) && presetId == "flat" && !MsebCalculator.isEnabled(context)) {
                MusicService.instance?.oboeDirectPlayer?.resetDspEq5Band()
            }
        }

        // 【V7.107】恢复 AutoEQ 预设 — OboeDirectPlayer 每切歌新建实例，AutoEQ 会丢失
        // 必须在 5-band EQ 恢复之后,因为 resetDspEq5Band() 也会清 g_autoEqEnabled
        // 【V7.108】DSP Mode 为 OFF 时不恢复 AutoEQ，避免切歌后 EQ 自动打开
        if (isOboeMode(context)) {
            val dspMode = context.getSharedPreferences("dsp_mode", Context.MODE_PRIVATE)
                .getInt("mode", -1)
            if (dspMode >= 0) {
                val savedAutoEq = getAutoEqPreset(context)
                if (savedAutoEq != null) {
                    applyAutoEqPreset(savedAutoEq, context)
                    Log.i(TAG, "AutoEQ preset restored in restoreSettings: $savedAutoEq")
                }
            }
        }

        Log.d(TAG, "Restored: enabled=$enabled, preset=$presetId")
    }

    fun getBandFrequencies(): List<String> {
        val eq = equalizer ?: return emptyList()
        val frequencies = mutableListOf<String>()
        val numBands = eq.numberOfBands.toInt()
        for (i in 0 until numBands) {
            val freqKHz = eq.getCenterFreq(i.toShort()) / 1000f
            frequencies.add(if (freqKHz >= 1000) "${freqKHz / 1000}k" else "${freqKHz.toInt()}Hz")
        }
        return frequencies
    }
}



