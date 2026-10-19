package com.sdw.music.player.core.audio.dsp
import com.sdw.music.player.core.audio.dsp.EqualizerManager

import android.content.Context
import com.sdw.music.player.R

/**
 * 曲风 / 风格预设模板（可编辑 PEQ，10 段标准频点）。
 *
 * 每套都是一组 ParametricEqManager.Band：载入后即为可逐段微调的参数 EQ，
 * 频率 / 增益 / Q 都能在界面里继续改。
 *
 * 频点采用经典 10 段图示 EQ 的标准档位：
 *   31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k Hz
 * 首段（31 Hz）用低架（Low Shelf）覆盖超低频，其余为峰式（Peaking, Q=1.2）。
 * preamp 为防削波的整体衰减（按最大提升量预留）。
 *
 * 名称 / 描述走 string 资源（nameRes / descRes），中英文随系统语言切换。
 */
object GenrePresets {

    data class GenrePreset(
        val nameRes: Int,
        val descRes: Int,
        val preamp: Float,
        val bands: List<ParametricEqManager.Band>
    )

    private fun ls(freq: Float, gain: Float, q: Float = 0.7f) =
        ParametricEqManager.Band(ParametricEqManager.TYPE_LOW_SHELF, freq, gain, q, true)
    private fun pk(freq: Float, gain: Float, q: Float = 1.2f) =
        ParametricEqManager.Band(ParametricEqManager.TYPE_PEAKING, freq, gain, q, true)

    /** 10 段标准频点（首段 31 Hz 低架，其余峰式） */
    private fun shape(vararg gains: Float): List<ParametricEqManager.Band> {
        require(gains.size == 10) { "genre preset needs exactly 10 gains" }
        val freqs = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        return freqs.mapIndexed { i, f ->
            if (i == 0) ls(f, gains[0]) else pk(f, gains[i])
        }
    }

    /** 5 段经典图示 EQ 档位（60/230/910/3600/14000 Hz）：低架 + 三段峰 + 高架。
     *  用于把原 EqualizerManager 的 5 段品牌 / 通用预设迁移为可编辑参数 EQ 模板。 */
    private fun shape5(vararg gains: Float): List<ParametricEqManager.Band> {
        require(gains.size == 5) { "5-band preset needs exactly 5 gains" }
        val freqs = listOf(60f, 230f, 910f, 3600f, 14000f)
        val types = listOf(
            ParametricEqManager.TYPE_LOW_SHELF,
            ParametricEqManager.TYPE_PEAKING,
            ParametricEqManager.TYPE_PEAKING,
            ParametricEqManager.TYPE_PEAKING,
            ParametricEqManager.TYPE_HIGH_SHELF
        )
        return freqs.mapIndexed { i, f ->
            ParametricEqManager.Band(types[i], f, gains[i], if (i == 0 || i == 4) 0.7f else 1.2f, true)
        }
    }

    val presets: List<GenrePreset> = listOf(
        GenrePreset(R.string.eq_genre_pop, R.string.eq_genre_pop_desc, -2f, shape(
            2f, 3f, 1.5f, 0f, 0.5f, 1.5f, 2f, 1.5f, 2f, 2f
        )),
        GenrePreset(R.string.eq_genre_rock, R.string.eq_genre_rock_desc, -3f, shape(
            4f, 4f, 2.5f, 0.5f, -1f, -0.5f, 1f, 2f, 3.5f, 3f
        )),
        GenrePreset(R.string.eq_genre_classical, R.string.eq_genre_classical_desc, -1.5f, shape(
            1f, 1.5f, 0.5f, 0f, 0f, 1f, 2f, 2.5f, 2f, 1.5f
        )),
        GenrePreset(R.string.eq_genre_jazz, R.string.eq_genre_jazz_desc, -1f, shape(
            1.5f, 2.5f, 2f, 1f, 0f, 0.5f, 0.5f, 0.5f, 0f, 0f
        )),
        GenrePreset(R.string.eq_genre_vocal, R.string.eq_genre_vocal_desc, -1.5f, shape(
            -2f, -1.5f, 0f, 1f, 2f, 3f, 2.5f, 1f, 0f, -0.5f
        )),
        GenrePreset(R.string.eq_genre_edm, R.string.eq_genre_edm_desc, -3.5f, shape(
            5f, 5.5f, 3f, 0f, -1f, 0f, 1f, 2.5f, 4f, 4f
        )),
        GenrePreset(R.string.eq_genre_bass, R.string.eq_genre_bass_desc, -4f, shape(
            6f, 6f, 4.5f, 2.5f, 0.5f, 0f, 0f, 0f, 0f, 0f
        )),
        GenrePreset(R.string.eq_genre_bright, R.string.eq_genre_bright_desc, -2f, shape(
            0f, 0f, 0f, 0f, 0.5f, 1f, 2f, 3.5f, 4f, 3.5f
        )),
        GenrePreset(R.string.eq_genre_movie, R.string.eq_genre_movie_desc, -2f, shape(
            3f, 3.5f, 2f, 0.5f, 1f, 1.5f, 1f, 1f, 1.5f, 1.5f
        )),
        GenrePreset(R.string.eq_genre_game, R.string.eq_genre_game_desc, -2f, shape(
            2f, 2.5f, 1.5f, 0f, 0f, 0.5f, 2f, 3f, 3f, 1.5f
        )),
        GenrePreset(R.string.eq_genre_live, R.string.eq_genre_live_desc, -2f, shape(
            2f, 3f, 1.5f, 0.5f, 0.5f, 1f, 2f, 2f, 2.5f, 2f
        )),
        GenrePreset(R.string.eq_genre_warm, R.string.eq_genre_warm_desc, -1.5f, shape(
            3f, 3.5f, 2.5f, 1.5f, 0.5f, -0.5f, -1f, -1.5f, -1f, -0.5f
        )),
        GenrePreset(R.string.eq_genre_acoustic, R.string.eq_genre_acoustic_desc, -1f, shape(
            1f, 1.5f, 1.5f, 1f, 1f, 1.5f, 1f, 1f, 1.5f, 1f
        )),
        GenrePreset(R.string.eq_genre_soft, R.string.eq_genre_soft_desc, -1f, shape(
            1f, 2f, 1.5f, 0.5f, 0f, -0.5f, -1f, -1.5f, -1f, -0.5f
        ))
    )

    /** 品牌调音：原 EqualizerManager 5 段品牌预设迁移而来（增益 = 原 millibel / 100），
     *  频率按经典 5 段档位重建，描述复用既有 eq_desc_* 资源。 */
    val brandPresets: List<GenrePreset> = listOf(
        GenrePreset(R.string.eq_preset_bose_warm, R.string.eq_desc_bose_warm, 0f, shape5(6f, 3f, 0f, -2f, -3f)),
        GenrePreset(R.string.eq_preset_bose_vibrant, R.string.eq_desc_bose_vibrant, 0f, shape5(4f, 2f, 0f, 3f, 5f)),
        GenrePreset(R.string.eq_preset_sony_bright, R.string.eq_desc_sony_bright, 0f, shape5(2f, 1f, 2f, 4f, 6f)),
        GenrePreset(R.string.eq_preset_sony_bass, R.string.eq_desc_sony_bass, 0f, shape5(8f, 6f, 2f, -1f, -2f)),
        GenrePreset(R.string.eq_preset_sony_balanced, R.string.eq_desc_sony_balanced, 0f, shape5(2f, 1f, 1f, 1f, 2f)),
        GenrePreset(R.string.eq_preset_sennheiser_warm, R.string.eq_desc_sennheiser_warm, 0f, shape5(3f, 4f, 3f, 1f, 0f)),
        GenrePreset(R.string.eq_preset_sennheiser_analytical, R.string.eq_desc_sennheiser_analytical, 0f, shape5(-1f, 0f, 2f, 4f, 3f)),
        GenrePreset(R.string.eq_preset_jbl_pure_bass, R.string.eq_desc_jbl_pure_bass, 0f, shape5(9f, 7f, 1f, -2f, -3f)),
        GenrePreset(R.string.eq_preset_jbl_club, R.string.eq_desc_jbl_club, 0f, shape5(7f, 5f, 0f, 2f, 4f)),
        GenrePreset(R.string.eq_preset_akg_studio, R.string.eq_desc_akg_studio, 0f, shape5(1f, 2f, 3f, 2f, 1f)),
        GenrePreset(R.string.eq_preset_akg_reference, R.string.eq_desc_akg_reference, 0f, shape5(0f, 1f, 1f, 1f, 0f)),
        GenrePreset(R.string.eq_preset_airpods_neutral, R.string.eq_desc_airpods_neutral, 0f, shape5(1f, 1f, 1f, 1f, 1f)),
        GenrePreset(R.string.eq_preset_airpods_spatial, R.string.eq_desc_airpods_spatial, 0f, shape5(2f, 0f, -1f, 0f, 2f)),
        GenrePreset(R.string.eq_preset_samsung_dynamic, R.string.eq_desc_samsung_dynamic, 0f, shape5(4f, 2f, 0f, 2f, 4f)),
        GenrePreset(R.string.eq_preset_samsung_clear, R.string.eq_desc_samsung_clear, 0f, shape5(1f, 3f, 5f, 3f, 1f)),
        GenrePreset(R.string.eq_preset_ath_monitor, R.string.eq_desc_ath_monitor, 0f, shape5(0f, 1f, 2f, 1f, 0f)),
        GenrePreset(R.string.eq_preset_ath_smooth, R.string.eq_desc_ath_smooth, 0f, shape5(2f, 3f, 2f, 1f, 0f)),
        GenrePreset(R.string.eq_preset_beats_bass, R.string.eq_desc_beats_bass, 0f, shape5(10f, 8f, 2f, -1f, -2f)),
        GenrePreset(R.string.eq_preset_beats_pop, R.string.eq_desc_beats_pop, 0f, shape5(6f, 4f, 1f, 2f, 3f)),
        GenrePreset(R.string.eq_preset_shure_monitor, R.string.eq_desc_shure_monitor, 0f, shape5(0f, 0f, 1f, 0f, 0f)),
        GenrePreset(R.string.eq_preset_marshall_rock, R.string.eq_desc_marshall_rock, 0f, shape5(4f, 1f, -1f, 3f, 5f))
    )

    /** 通用调音：原 Steven 专属 / Bass Boost / Treble Boost 等 5 段预设迁移而来。 */
    val tonePresets: List<GenrePreset> = listOf(
        GenrePreset(R.string.eq_preset_steven_special, R.string.eq_desc_steven_special, 0f, shape5(0f, 0f, -1f, -4f, -9f)),
        GenrePreset(R.string.eq_preset_bass_boost, R.string.eq_desc_bass_boost, 0f, shape5(10f, 5f, 0f, -2f, -4f)),
        GenrePreset(R.string.eq_preset_treble_boost, R.string.eq_desc_treble_boost, 0f, shape5(-3f, -1f, 2f, 5f, 8f))
    )

    /**
     * 曲风模板本身是 5/10 段标准频点。载入后扩成完整 64 段参数 EQ：
     * 模板频段留在原位（保留其真实频点），其余段补为平坦默认段，
     * 用户即可在模板基础上继续微调第 11~64 段（修复「先选 10 段模板、再调 11 段之后无效」）。
     */
    fun toPreset(g: GenrePreset, context: Context): ParametricEqManager.Preset {
        val base = g.bands
        val bands = if (base.size < ParametricEqManager.NATIVE_MAX_BANDS) {
            base + ParametricEqManager.defaultBands(ParametricEqManager.NATIVE_MAX_BANDS).drop(base.size)
        } else {
            base
        }
        return ParametricEqManager.Preset(name = context.getString(g.nameRes), preamp = g.preamp, bands = bands)
    }
}
