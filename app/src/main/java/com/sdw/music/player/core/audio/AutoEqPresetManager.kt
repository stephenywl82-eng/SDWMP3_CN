package com.sdw.music.player.core.audio

import android.util.Log
import com.sdw.music.player.OboeDirectPlayer

/**
 * AutoEQ 风格耳机修正预设管理器
 *
 * 基于On源 [AutoEq](https://github.com/jaakkopasanen/AutoEq) 项目的理念：
 * - 每个耳机型号有专属的 10 段 Parametric EQ 修正
 * - 目标：补偿耳机的频响缺陷，逼近 Harman 目标曲线
 * - 带 Preamp 负增益防止数字削波
 *
 * 筛选原则（2026）：手册足够大的耳机型号，覆盖常见 HiFi / TWS / 头戴
 */
data class AutoEqFilter(
    val type: String,       // "PK"=Peaking, "HS"=HighShelf, "LS"=LowShelf
    val freq: Float,        // 中心/截止频率 (Hz)
    val gain: Float,        // 增益 (dB), ±12dB
    val q: Float            // Q 值 (0.1-5.0)
)

data class AutoEqPreset(
    val name: String,       // 显示名称 (如 "Sony WH-1000XM4")
    val brand: String,      // 品牌 (用于分组)
    val type: String,       // "over-ear" | "in-ear" | "earbud"
    val preamp: Float,      // 推荐 preamp (负值)
    val filters: List<AutoEqFilter>  // 10 段参数 EQ
)

object AutoEqPresetManager {
    private const val TAG = "AutoEqPresetManager"

    /** filterType: 0=Peaking, 1=HighShelf, 2=LowShelf */
    fun filterTypeInt(type: String): Int = when (type) {
        "HS" -> 1
        "LS" -> 2
        else -> 0  // PK/LSC/HSC all map to Peaking
    }

    /** 预设数据库 — 精选 48 款热门耳机 */
    val presets: List<AutoEqPreset> = listOf(
        // =========== Sony ===========
        AutoEqPreset("Sony WH-1000XM4", "Sony", "over-ear", -6.1f, listOf(
    AutoEqFilter("LS", 105.0f, -4.2f, 0.70f),
    AutoEqFilter("PK", 143.0f, -5.2f, 1.10f),
    AutoEqFilter("PK", 2289.0f, +6.1f, 1.57f),
    AutoEqFilter("PK", 56.0f, +1.2f, 1.19f),
    AutoEqFilter("PK", 5144.0f, -3.2f, 6.00f),
    AutoEqFilter("HS", 10000.0f, -1.0f, 0.70f),
    AutoEqFilter("PK", 407.0f, +1.7f, 3.14f),
    AutoEqFilter("PK", 6715.0f, +3.0f, 5.99f),
    AutoEqFilter("PK", 1007.0f, +1.0f, 3.41f),
    AutoEqFilter("PK", 576.0f, -1.2f, 3.55f),
)),
        AutoEqPreset("Sony WH-1000XM5", "Sony", "over-ear", -6.2f, listOf(
    AutoEqFilter("LS", 105.0f, -3.2f, 0.70f),
    AutoEqFilter("PK", 2448.0f, +6.9f, 2.46f),
    AutoEqFilter("PK", 173.0f, -5.6f, 0.96f),
    AutoEqFilter("PK", 3028.0f, -5.4f, 2.03f),
    AutoEqFilter("PK", 1327.0f, +3.3f, 0.58f),
    AutoEqFilter("HS", 10000.0f, +4.9f, 0.70f),
    AutoEqFilter("PK", 6110.0f, -2.3f, 5.81f),
    AutoEqFilter("PK", 875.0f, -1.2f, 4.07f),
    AutoEqFilter("PK", 1197.0f, +1.0f, 3.28f),
    AutoEqFilter("PK", 63.0f, +0.4f, 2.13f),
)),
        AutoEqPreset("Sony WF-1000XM5", "Sony", "in-ear", -2.6f, listOf(
    AutoEqFilter("LS", 105.0f, +5.4f, 0.70f),
    AutoEqFilter("PK", 68.0f, -4.8f, 0.27f),
    AutoEqFilter("PK", 1377.0f, +2.6f, 0.31f),
    AutoEqFilter("PK", 1806.0f, -3.5f, 1.16f),
    AutoEqFilter("PK", 6875.0f, +1.8f, 3.49f),
    AutoEqFilter("HS", 10000.0f, +2.3f, 0.70f),
    AutoEqFilter("PK", 3757.0f, +0.3f, 2.56f),
    AutoEqFilter("PK", 5057.0f, -0.7f, 5.70f),
    AutoEqFilter("PK", 449.0f, +0.3f, 4.15f),
    AutoEqFilter("PK", 677.0f, -0.2f, 2.75f),
)),
        AutoEqPreset("Sony WF-1000XM4", "Sony", "in-ear", -7.5f, listOf(
    AutoEqFilter("LS", 105.0f, -0.6f, 0.70f),
    AutoEqFilter("PK", 176.0f, -4.2f, 0.60f),
    AutoEqFilter("PK", 9973.0f, +3.7f, 1.01f),
    AutoEqFilter("PK", 6316.0f, +4.4f, 0.91f),
    AutoEqFilter("PK", 498.0f, -1.4f, 1.46f),
    AutoEqFilter("HS", 10000.0f, +3.7f, 0.70f),
    AutoEqFilter("PK", 1732.0f, +1.2f, 2.28f),
    AutoEqFilter("PK", 3942.0f, -0.8f, 3.10f),
    AutoEqFilter("PK", 978.0f, -0.3f, 1.49f),
    AutoEqFilter("PK", 38.0f, -0.2f, 2.02f),
)),
        AutoEqPreset("Sony MDR-7506", "Sony", "over-ear", -5.8f, listOf(
    AutoEqFilter("LS", 105.0f, +10.3f, 0.70f),
    AutoEqFilter("PK", 5435.0f, -3.8f, 0.90f),
    AutoEqFilter("PK", 847.0f, +1.9f, 0.70f),
    AutoEqFilter("PK", 230.0f, +3.7f, 2.08f),
    AutoEqFilter("PK", 48.0f, -9.5f, 0.52f),
    AutoEqFilter("HS", 10000.0f, +3.8f, 0.70f),
    AutoEqFilter("PK", 7530.0f, -1.4f, 3.26f),
    AutoEqFilter("PK", 2916.0f, -1.9f, 5.57f),
    AutoEqFilter("PK", 3748.0f, +2.6f, 5.91f),
    AutoEqFilter("PK", 4456.0f, -1.6f, 6.00f),
)),

        // =========== Apple ===========
        AutoEqPreset("AirPods Max", "Apple", "over-ear", -4.7f, listOf(
    AutoEqFilter("LS", 105.0f, -3.0f, 0.70f),
    AutoEqFilter("PK", 7273.0f, +3.6f, 2.41f),
    AutoEqFilter("PK", 218.0f, -2.9f, 1.41f),
    AutoEqFilter("PK", 1031.0f, -3.2f, 0.99f),
    AutoEqFilter("PK", 3185.0f, +3.1f, 0.56f),
    AutoEqFilter("HS", 10000.0f, -5.5f, 0.70f),
    AutoEqFilter("PK", 9508.0f, +2.7f, 2.20f),
    AutoEqFilter("PK", 66.0f, +0.6f, 1.65f),
    AutoEqFilter("PK", 4045.0f, +2.1f, 5.82f),
    AutoEqFilter("PK", 4834.0f, -1.8f, 6.00f),
)),
        AutoEqPreset("AirPods Pro 2", "Apple", "in-ear", -3.6f, listOf(
    AutoEqFilter("LS", 105.0f, +2.6f, 0.70f),
    AutoEqFilter("PK", 364.0f, -1.9f, 1.62f),
    AutoEqFilter("PK", 4461.0f, +3.3f, 2.37f),
    AutoEqFilter("PK", 77.0f, +1.6f, 1.67f),
    AutoEqFilter("PK", 2828.0f, -2.4f, 2.51f),
    AutoEqFilter("HS", 10000.0f, -0.4f, 0.70f),
    AutoEqFilter("PK", 1257.0f, +1.7f, 2.59f),
    AutoEqFilter("PK", 9292.0f, +1.1f, 2.33f),
    AutoEqFilter("PK", 1734.0f, -1.0f, 2.26f),
    AutoEqFilter("PK", 1320.0f, +0.4f, 2.26f),
)),
        AutoEqPreset("AirPods Pro", "Apple", "in-ear", -6.0f, listOf(
    AutoEqFilter("LS", 105.0f, +2.6f, 0.70f),
    AutoEqFilter("PK", 514.0f, -4.4f, 0.68f),
    AutoEqFilter("PK", 8903.0f, +6.0f, 1.66f),
    AutoEqFilter("PK", 183.0f, +2.0f, 0.77f),
    AutoEqFilter("PK", 4613.0f, +3.4f, 2.46f),
    AutoEqFilter("HS", 10000.0f, -0.5f, 0.70f),
    AutoEqFilter("PK", 1517.0f, -1.0f, 2.33f),
    AutoEqFilter("PK", 929.0f, +1.2f, 2.75f),
    AutoEqFilter("PK", 44.0f, -0.5f, 2.16f),
    AutoEqFilter("PK", 618.0f, -0.5f, 2.86f),
)),

        // =========== Bose ===========
        AutoEqPreset("Bose QC45", "Bose", "over-ear", -2.9f, listOf(
    AutoEqFilter("LS", 105.0f, +9.2f, 0.70f),
    AutoEqFilter("PK", 1581.0f, -7.3f, 1.60f),
    AutoEqFilter("PK", 45.0f, -9.5f, 0.37f),
    AutoEqFilter("PK", 1496.0f, +6.2f, 0.52f),
    AutoEqFilter("PK", 2274.0f, -6.0f, 2.01f),
    AutoEqFilter("HS", 10000.0f, -5.4f, 0.70f),
    AutoEqFilter("PK", 8777.0f, +3.0f, 1.92f),
    AutoEqFilter("PK", 6253.0f, -4.1f, 5.64f),
    AutoEqFilter("PK", 4264.0f, +2.0f, 5.82f),
    AutoEqFilter("PK", 376.0f, +0.4f, 2.21f),
)),
        AutoEqPreset("Bose QC Ultra", "Bose", "over-ear", -4.5f, listOf(
    AutoEqFilter("LS", 105.0f, -7.9f, 0.70f),
    AutoEqFilter("PK", 150.0f, -2.3f, 1.77f),
    AutoEqFilter("PK", 4546.0f, +4.7f, 2.42f),
    AutoEqFilter("PK", 2446.0f, -6.2f, 3.41f),
    AutoEqFilter("PK", 1635.0f, +4.1f, 3.15f),
    AutoEqFilter("HS", 10000.0f, -3.0f, 0.70f),
    AutoEqFilter("PK", 8346.0f, +2.6f, 2.70f),
    AutoEqFilter("PK", 421.0f, +1.2f, 1.70f),
    AutoEqFilter("PK", 36.0f, -1.1f, 3.21f),
    AutoEqFilter("PK", 986.0f, -1.2f, 3.01f),
)),
        AutoEqPreset("Bose QC Earbuds II", "Bose", "in-ear", -4.9f, listOf(
    AutoEqFilter("LS", 105.0f, -4.5f, 0.70f),
    AutoEqFilter("PK", 1078.0f, +4.6f, 1.23f),
    AutoEqFilter("PK", 2228.0f, -6.3f, 2.33f),
    AutoEqFilter("PK", 29.0f, -1.9f, 1.66f),
    AutoEqFilter("PK", 189.0f, -1.3f, 3.48f),
    AutoEqFilter("HS", 10000.0f, +4.9f, 0.70f),
    AutoEqFilter("PK", 6139.0f, -1.3f, 3.42f),
    AutoEqFilter("PK", 358.0f, -1.1f, 2.86f),
    AutoEqFilter("PK", 504.0f, +0.8f, 3.06f),
    AutoEqFilter("PK", 5318.0f, +1.1f, 5.92f),
)),

        // =========== Sennheiser ===========
        AutoEqPreset("Sennheiser HD600", "Sennheiser", "over-ear", -6.3f, listOf(
    AutoEqFilter("LS", 105.0f, +6.5f, 0.70f),
    AutoEqFilter("PK", 125.0f, -2.7f, 0.55f),
    AutoEqFilter("PK", 8445.0f, +3.3f, 1.61f),
    AutoEqFilter("PK", 522.0f, +0.7f, 1.02f),
    AutoEqFilter("PK", 1298.0f, -1.2f, 2.14f),
    AutoEqFilter("HS", 10000.0f, -3.1f, 0.70f),
    AutoEqFilter("PK", 3158.0f, -1.8f, 3.67f),
    AutoEqFilter("PK", 2166.0f, +0.9f, 3.32f),
    AutoEqFilter("PK", 6639.0f, +2.2f, 5.82f),
    AutoEqFilter("PK", 5433.0f, -1.2f, 5.70f),
)),
        AutoEqPreset("Sennheiser HD650", "Sennheiser", "over-ear", -6.1f, listOf(
    AutoEqFilter("LS", 105.0f, +6.4f, 0.70f),
    AutoEqFilter("PK", 8800.0f, +5.1f, 1.42f),
    AutoEqFilter("PK", 118.0f, -3.1f, 0.50f),
    AutoEqFilter("PK", 37.0f, +0.7f, 3.96f),
    AutoEqFilter("PK", 3169.0f, -1.7f, 3.89f),
    AutoEqFilter("HS", 10000.0f, -2.1f, 0.70f),
    AutoEqFilter("PK", 1227.0f, -1.2f, 2.53f),
    AutoEqFilter("PK", 2055.0f, +1.2f, 3.23f),
    AutoEqFilter("PK", 587.0f, +0.4f, 1.19f),
    AutoEqFilter("PK", 5332.0f, -1.1f, 5.75f),
)),
        AutoEqPreset("Sennheiser HD660S2", "Sennheiser", "over-ear", -6.4f, listOf(
    AutoEqFilter("LS", 105.0f, +7.6f, 0.70f),
    AutoEqFilter("PK", 98.0f, -3.5f, 0.31f),
    AutoEqFilter("PK", 8089.0f, +4.7f, 0.53f),
    AutoEqFilter("PK", 5567.0f, -6.6f, 3.92f),
    AutoEqFilter("PK", 1206.0f, -2.0f, 2.46f),
    AutoEqFilter("HS", 10000.0f, -3.8f, 0.70f),
    AutoEqFilter("PK", 2218.0f, +1.1f, 3.75f),
    AutoEqFilter("PK", 3206.0f, -1.2f, 3.51f),
    AutoEqFilter("PK", 4367.0f, +1.3f, 6.00f),
    AutoEqFilter("PK", 115.0f, +0.1f, 1.26f),
)),
        AutoEqPreset("Sennheiser HD800S", "Sennheiser", "over-ear", -6.2f, listOf(
    AutoEqFilter("LS", 105.0f, +6.6f, 0.70f),
    AutoEqFilter("PK", 142.0f, -2.3f, 0.30f),
    AutoEqFilter("PK", 1959.0f, +2.5f, 0.88f),
    AutoEqFilter("PK", 5679.0f, -4.4f, 4.36f),
    AutoEqFilter("PK", 212.0f, -0.5f, 2.36f),
    AutoEqFilter("HS", 10000.0f, -4.3f, 0.70f),
    AutoEqFilter("PK", 1043.0f, -1.0f, 2.89f),
    AutoEqFilter("PK", 1445.0f, +1.0f, 3.43f),
    AutoEqFilter("PK", 2640.0f, -0.9f, 4.01f),
    AutoEqFilter("PK", 3421.0f, +1.3f, 5.47f),
)),
        AutoEqPreset("Sennheiser IE600", "Sennheiser", "in-ear", -5.3f, listOf(
    AutoEqFilter("LS", 105.0f, -2.7f, 0.70f),
    AutoEqFilter("PK", 4374.0f, +5.0f, 1.28f),
    AutoEqFilter("PK", 196.0f, -1.9f, 1.50f),
    AutoEqFilter("PK", 1727.0f, -2.8f, 1.16f),
    AutoEqFilter("PK", 798.0f, +1.5f, 1.45f),
    AutoEqFilter("HS", 10000.0f, -7.6f, 0.70f),
    AutoEqFilter("PK", 72.0f, +1.4f, 1.93f),
    AutoEqFilter("PK", 5857.0f, +3.3f, 5.80f),
    AutoEqFilter("PK", 143.0f, -0.7f, 3.18f),
    AutoEqFilter("PK", 4796.0f, -0.6f, 4.12f),
)),

        // =========== Beyerdynamic ===========
        AutoEqPreset("Beyerdynamic DT770 Pro", "Beyerdynamic", "over-ear", -5.1f, listOf(
    AutoEqFilter("LS", 105.0f, -3.0f, 0.70f),
    AutoEqFilter("PK", 6430.0f, -4.2f, 0.97f),
    AutoEqFilter("PK", 3684.0f, +6.8f, 2.88f),
    AutoEqFilter("PK", 213.0f, +3.5f, 2.46f),
    AutoEqFilter("PK", 139.0f, -3.9f, 4.92f),
    AutoEqFilter("HS", 10000.0f, -5.4f, 0.70f),
    AutoEqFilter("PK", 93.0f, +3.5f, 3.00f),
    AutoEqFilter("PK", 9561.0f, +2.3f, 1.86f),
    AutoEqFilter("PK", 45.0f, -1.3f, 1.74f),
    AutoEqFilter("PK", 119.0f, -2.1f, 5.83f),
)),
        AutoEqPreset("Beyerdynamic DT990 Pro", "Beyerdynamic", "over-ear", -6.7f, listOf(
    AutoEqFilter("LS", 105.0f, +10.1f, 0.70f),
    AutoEqFilter("PK", 541.0f, +3.1f, 0.52f),
    AutoEqFilter("PK", 64.0f, -7.4f, 0.38f),
    AutoEqFilter("PK", 7731.0f, -4.6f, 1.06f),
    AutoEqFilter("PK", 640.0f, +0.6f, 2.47f),
    AutoEqFilter("HS", 10000.0f, -9.8f, 0.70f),
    AutoEqFilter("PK", 7136.0f, +3.1f, 2.08f),
    AutoEqFilter("PK", 5897.0f, -4.8f, 5.30f),
    AutoEqFilter("PK", 4193.0f, +1.1f, 1.51f),
    AutoEqFilter("PK", 9436.0f, +2.7f, 3.53f),
)),

        // =========== AKG ===========
        AutoEqPreset("AKG K371", "AKG", "over-ear", -5.6f, listOf(
    AutoEqFilter("LS", 105.0f, -2.7f, 0.70f),
    AutoEqFilter("PK", 182.0f, -2.3f, 1.23f),
    AutoEqFilter("PK", 4038.0f, +5.0f, 3.60f),
    AutoEqFilter("PK", 67.0f, +3.1f, 1.41f),
    AutoEqFilter("PK", 1066.0f, -0.8f, 2.57f),
    AutoEqFilter("HS", 10000.0f, +2.4f, 0.70f),
    AutoEqFilter("PK", 5564.0f, -1.6f, 3.89f),
    AutoEqFilter("PK", 4232.0f, +0.9f, 5.03f),
    AutoEqFilter("PK", 524.0f, +0.3f, 1.66f),
    AutoEqFilter("PK", 2048.0f, +0.5f, 4.67f),
)),
        AutoEqPreset("AKG K702", "AKG", "over-ear", -6.1f, listOf(
    AutoEqFilter("LS", 105.0f, +7.1f, 0.70f),
    AutoEqFilter("PK", 119.0f, -2.7f, 0.23f),
    AutoEqFilter("PK", 9459.0f, +3.2f, 3.19f),
    AutoEqFilter("PK", 730.0f, +3.2f, 1.22f),
    AutoEqFilter("PK", 2241.0f, -3.5f, 3.80f),
    AutoEqFilter("HS", 10000.0f, -1.7f, 0.70f),
    AutoEqFilter("PK", 3721.0f, +3.4f, 2.02f),
    AutoEqFilter("PK", 5483.0f, -4.7f, 4.79f),
    AutoEqFilter("PK", 2633.0f, -2.0f, 5.29f),
    AutoEqFilter("PK", 56.0f, -0.6f, 2.62f),
)),

        // =========== Audio-Technica ===========
        AutoEqPreset("ATH-M50x", "Audio-Technica", "over-ear", -3.1f, listOf(
    AutoEqFilter("LS", 105.0f, +0.6f, 0.70f),
    AutoEqFilter("PK", 156.0f, -5.2f, 0.73f),
    AutoEqFilter("PK", 326.0f, +5.3f, 1.59f),
    AutoEqFilter("PK", 7077.0f, +2.8f, 2.22f),
    AutoEqFilter("PK", 3483.0f, +2.1f, 5.82f),
    AutoEqFilter("HS", 10000.0f, -4.1f, 0.70f),
    AutoEqFilter("PK", 45.0f, -1.1f, 1.90f),
    AutoEqFilter("PK", 66.0f, +1.4f, 3.59f),
    AutoEqFilter("PK", 787.0f, -0.5f, 1.79f),
    AutoEqFilter("PK", 1640.0f, +0.9f, 3.41f),
)),
        AutoEqPreset("ATH-R70x", "Audio-Technica", "over-ear", -6.6f, listOf(
    AutoEqFilter("LS", 105.0f, +7.5f, 0.70f),
    AutoEqFilter("PK", 4308.0f, +4.8f, 0.51f),
    AutoEqFilter("PK", 93.0f, -4.4f, 0.43f),
    AutoEqFilter("PK", 3519.0f, -3.2f, 4.05f),
    AutoEqFilter("PK", 2149.0f, -3.1f, 0.96f),
    AutoEqFilter("HS", 10000.0f, -1.5f, 0.70f),
    AutoEqFilter("PK", 4518.0f, +1.3f, 6.00f),
    AutoEqFilter("PK", 58.0f, -0.6f, 2.31f),
    AutoEqFilter("PK", 34.0f, +0.7f, 3.60f),
    AutoEqFilter("PK", 106.0f, +0.3f, 1.84f),
)),

        // =========== Hifiman ===========
        AutoEqPreset("Hifiman Sundara", "Hifiman", "over-ear", -6.1f, listOf(
    AutoEqFilter("LS", 105.0f, +6.5f, 0.70f),
    AutoEqFilter("PK", 127.0f, -2.0f, 0.29f),
    AutoEqFilter("PK", 2009.0f, +6.5f, 2.96f),
    AutoEqFilter("PK", 4085.0f, -4.4f, 1.19f),
    AutoEqFilter("PK", 7580.0f, +2.3f, 0.66f),
    AutoEqFilter("HS", 10000.0f, -1.6f, 0.70f),
    AutoEqFilter("PK", 63.0f, -1.2f, 2.40f),
    AutoEqFilter("PK", 38.0f, +0.9f, 2.27f),
    AutoEqFilter("PK", 789.0f, -1.6f, 4.10f),
    AutoEqFilter("PK", 5821.0f, +1.9f, 6.00f),
)),
        AutoEqPreset("Hifiman Edition XS", "Hifiman", "over-ear", -4.8f, listOf(
    AutoEqFilter("LS", 105.0f, +5.8f, 0.70f),
    AutoEqFilter("PK", 87.0f, -2.8f, 0.33f),
    AutoEqFilter("PK", 1896.0f, +4.0f, 1.92f),
    AutoEqFilter("PK", 2896.0f, -3.7f, 3.11f),
    AutoEqFilter("PK", 1420.0f, +1.3f, 3.89f),
    AutoEqFilter("HS", 10000.0f, -5.8f, 0.70f),
    AutoEqFilter("PK", 5585.0f, +1.7f, 6.00f),
    AutoEqFilter("PK", 70.0f, -0.4f, 2.12f),
    AutoEqFilter("PK", 111.0f, +0.4f, 1.64f),
    AutoEqFilter("PK", 927.0f, -0.9f, 6.00f),
)),

        // =========== Focal ===========
        AutoEqPreset("Focal Clear", "Focal", "over-ear", -5.8f, listOf(
    AutoEqFilter("LS", 105.0f, +7.3f, 0.70f),
    AutoEqFilter("PK", 8789.0f, +4.5f, 1.48f),
    AutoEqFilter("PK", 1236.0f, -3.8f, 2.26f),
    AutoEqFilter("PK", 73.0f, -3.6f, 0.36f),
    AutoEqFilter("PK", 4557.0f, +3.8f, 4.14f),
    AutoEqFilter("HS", 10000.0f, -1.6f, 0.70f),
    AutoEqFilter("PK", 2219.0f, +2.0f, 3.33f),
    AutoEqFilter("PK", 3136.0f, -1.1f, 3.33f),
    AutoEqFilter("PK", 1563.0f, -0.9f, 4.71f),
    AutoEqFilter("PK", 5934.0f, -0.8f, 6.00f),
)),
        AutoEqPreset("Focal Clear MG", "Focal", "over-ear", -6.8f, listOf(
    AutoEqFilter("LS", 105.0f, +5.9f, 0.70f),
    AutoEqFilter("PK", 8641.0f, +6.3f, 1.17f),
    AutoEqFilter("PK", 1198.0f, -4.0f, 1.73f),
    AutoEqFilter("PK", 94.0f, -4.2f, 0.30f),
    AutoEqFilter("PK", 4530.0f, +5.5f, 3.15f),
    AutoEqFilter("HS", 10000.0f, -3.4f, 0.70f),
    AutoEqFilter("PK", 2223.0f, +1.7f, 4.29f),
    AutoEqFilter("PK", 3196.0f, -1.7f, 3.02f),
    AutoEqFilter("PK", 7343.0f, +1.5f, 5.95f),
    AutoEqFilter("PK", 3986.0f, +1.4f, 6.00f),
)),

        // =========== SAMSUNG ===========
        AutoEqPreset("Samsung Galaxy Buds3 Pro", "Samsung", "in-ear", -2.8f, listOf(
    AutoEqFilter("LS", 105.0f, -5.5f, 0.70f),
    AutoEqFilter("PK", 185.0f, -2.5f, 0.80f),
    AutoEqFilter("PK", 2105.0f, +1.9f, 1.04f),
    AutoEqFilter("PK", 539.0f, +2.2f, 0.99f),
    AutoEqFilter("PK", 37.0f, -0.9f, 0.79f),
    AutoEqFilter("HS", 10000.0f, -0.8f, 0.70f),
    AutoEqFilter("PK", 5885.0f, -4.5f, 3.94f),
    AutoEqFilter("PK", 4086.0f, +2.6f, 5.16f),
    AutoEqFilter("PK", 7847.0f, -0.9f, 1.95f),
    AutoEqFilter("PK", 6672.0f, +2.9f, 5.97f),
)),
        AutoEqPreset("Samsung Galaxy Buds2 Pro", "Samsung", "in-ear", -2.3f, listOf(
    AutoEqFilter("LS", 105.0f, -2.3f, 0.70f),
    AutoEqFilter("PK", 7526.0f, +2.2f, 2.78f),
    AutoEqFilter("PK", 266.0f, -0.8f, 1.35f),
    AutoEqFilter("PK", 3426.0f, -2.3f, 3.70f),
    AutoEqFilter("PK", 64.0f, +2.2f, 1.18f),
    AutoEqFilter("HS", 10000.0f, -3.8f, 0.70f),
    AutoEqFilter("PK", 8817.0f, +1.6f, 2.28f),
    AutoEqFilter("PK", 2283.0f, +1.2f, 3.75f),
    AutoEqFilter("PK", 4955.0f, -1.2f, 5.11f),
    AutoEqFilter("PK", 977.0f, +1.0f, 5.18f),
)),

        // =========== JBL ===========
        AutoEqPreset("JBL Tour One M2", "JBL", "over-ear", -4.2f, listOf(
    AutoEqFilter("LS", 105.0f, -1.1f, 0.70f),
    AutoEqFilter("PK", 100.0f, -4.6f, 1.99f),
    AutoEqFilter("PK", 4836.0f, +4.6f, 2.38f),
    AutoEqFilter("PK", 7141.0f, -3.4f, 2.56f),
    AutoEqFilter("PK", 1429.0f, +1.5f, 2.12f),
    AutoEqFilter("HS", 10000.0f, -6.5f, 0.70f),
    AutoEqFilter("PK", 10000.0f, +3.1f, 1.67f),
    AutoEqFilter("PK", 44.0f, -0.9f, 2.59f),
    AutoEqFilter("PK", 2624.0f, -2.6f, 4.93f),
    AutoEqFilter("PK", 3573.0f, +1.4f, 4.68f),
)),

        // =========== Shure ===========
        AutoEqPreset("Shure SRH840", "Shure", "over-ear", -5.8f, listOf(
    AutoEqFilter("LS", 105.0f, +6.2f, 0.70f),
    AutoEqFilter("PK", 107.0f, -8.3f, 0.80f),
    AutoEqFilter("PK", 279.0f, +2.9f, 0.36f),
    AutoEqFilter("PK", 58.0f, +2.4f, 4.17f),
    AutoEqFilter("PK", 5991.0f, -1.8f, 2.60f),
    AutoEqFilter("HS", 10000.0f, -2.0f, 0.70f),
    AutoEqFilter("PK", 1946.0f, -0.7f, 2.63f),
    AutoEqFilter("PK", 39.0f, -0.4f, 3.26f),
    AutoEqFilter("PK", 261.0f, +0.6f, 3.14f),
    AutoEqFilter("PK", 332.0f, -0.8f, 4.70f),
)),
        AutoEqPreset("Shure Aonic 5", "Shure", "in-ear", -6.5f, listOf(
    AutoEqFilter("LS", 105.0f, +4.7f, 0.70f),
    AutoEqFilter("PK", 223.0f, -4.0f, 0.40f),
    AutoEqFilter("PK", 4796.0f, +6.0f, 1.40f),
    AutoEqFilter("PK", 9287.0f, +5.5f, 2.57f),
    AutoEqFilter("PK", 902.0f, +1.4f, 2.36f),
    AutoEqFilter("HS", 10000.0f, +6.0f, 0.70f),
    AutoEqFilter("PK", 9376.0f, -3.0f, 5.79f),
    AutoEqFilter("PK", 7219.0f, -2.0f, 5.98f),
    AutoEqFilter("PK", 3221.0f, -0.8f, 6.00f),
    AutoEqFilter("PK", 950.0f, 0.0f, 4.00f),
)),

        // =========== Beats ===========
        AutoEqPreset("Beats Studio Pro", "Beats", "over-ear", -3.8f, listOf(
    AutoEqFilter("LS", 105.0f, -3.1f, 0.70f),
    AutoEqFilter("PK", 8990.0f, -5.1f, 2.02f),
    AutoEqFilter("PK", 304.0f, +3.8f, 0.85f),
    AutoEqFilter("PK", 1720.0f, -3.0f, 1.19f),
    AutoEqFilter("PK", 67.0f, +6.0f, 2.55f),
    AutoEqFilter("HS", 10000.0f, +0.5f, 0.70f),
    AutoEqFilter("PK", 5052.0f, +3.7f, 4.43f),
    AutoEqFilter("PK", 3394.0f, -2.5f, 5.33f),
    AutoEqFilter("PK", 6509.0f, -2.0f, 5.62f),
    AutoEqFilter("PK", 734.0f, +0.7f, 3.38f),
)),
        AutoEqPreset("Beats Fit Pro", "Beats", "in-ear", -2.3f, listOf(
    AutoEqFilter("LS", 105.0f, +1.8f, 0.70f),
    AutoEqFilter("PK", 313.0f, +2.3f, 0.84f),
    AutoEqFilter("PK", 5866.0f, -4.4f, 4.07f),
    AutoEqFilter("PK", 2445.0f, -2.7f, 2.06f),
    AutoEqFilter("PK", 40.0f, -3.7f, 0.92f),
    AutoEqFilter("HS", 10000.0f, +1.0f, 0.70f),
    AutoEqFilter("PK", 1263.0f, -1.4f, 2.35f),
    AutoEqFilter("PK", 4035.0f, +1.8f, 4.11f),
    AutoEqFilter("PK", 5017.0f, -1.4f, 6.00f),
    AutoEqFilter("PK", 942.0f, +0.7f, 4.33f),
)),

        // =========== Marshall ===========
        AutoEqPreset("Marshall Monitor III", "Marshall", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 35f, 2.5f, 0.5f),
            AutoEqFilter("PK", 200f, -3.0f, 1.0f),
            AutoEqFilter("PK", 600f, 2.5f, 2.5f),
            AutoEqFilter("PK", 1200f, -1.5f, 3.0f),
            AutoEqFilter("PK", 2500f, 3.0f, 3.5f),
            AutoEqFilter("PK", 4000f, -3.0f, 4.0f),
            AutoEqFilter("PK", 5500f, 2.0f, 3.0f),
            AutoEqFilter("PK", 7500f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.5f, 2.0f)
        )),

        // =========== Jabra ===========
        AutoEqPreset("Jabra Elite 10", "Jabra", "in-ear", -2.3f, listOf(
    AutoEqFilter("LS", 105.0f, -2.4f, 0.70f),
    AutoEqFilter("PK", 149.0f, +2.7f, 1.80f),
    AutoEqFilter("PK", 40.0f, -1.1f, 0.89f),
    AutoEqFilter("PK", 267.0f, -2.4f, 2.68f),
    AutoEqFilter("PK", 452.0f, +2.5f, 2.74f),
    AutoEqFilter("HS", 10000.0f, -3.0f, 0.70f),
    AutoEqFilter("PK", 6808.0f, -3.6f, 5.45f),
    AutoEqFilter("PK", 9428.0f, -2.3f, 2.35f),
    AutoEqFilter("PK", 3252.0f, +2.0f, 2.24f),
    AutoEqFilter("PK", 4675.0f, -2.5f, 5.37f),
)),
        AutoEqPreset("Jabra Elite 8 Active", "Jabra", "in-ear", -2.7f, listOf(
    AutoEqFilter("LS", 105.0f, +0.2f, 0.70f),
    AutoEqFilter("PK", 70.0f, -6.7f, 0.58f),
    AutoEqFilter("PK", 303.0f, +3.2f, 0.59f),
    AutoEqFilter("PK", 2822.0f, +2.5f, 2.80f),
    AutoEqFilter("PK", 704.0f, -2.1f, 2.09f),
    AutoEqFilter("HS", 10000.0f, -3.8f, 0.70f),
    AutoEqFilter("PK", 5730.0f, -3.5f, 3.48f),
    AutoEqFilter("PK", 4450.0f, +2.1f, 2.57f),
    AutoEqFilter("PK", 1405.0f, -0.9f, 3.07f),
    AutoEqFilter("PK", 6830.0f, -1.2f, 5.84f),
)),

        // =========== OnePlus ===========
        AutoEqPreset("OnePlus Buds Pro 3", "OnePlus", "in-ear", -3.0f, listOf(
            AutoEqFilter("LS", 70f, 0.5f, 0.7f),
            AutoEqFilter("PK", 200f, -1.5f, 1.0f),
            AutoEqFilter("PK", 550f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.0f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4200f, -2.0f, 4.0f),
            AutoEqFilter("PK", 6000f, 2.5f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),

        // =========== Moondrop (Chi-Fi) ===========
        AutoEqPreset("Moondrop Blessing 3", "Moondrop", "in-ear", -2.9f, listOf(
    AutoEqFilter("LS", 105.0f, +2.5f, 0.70f),
    AutoEqFilter("PK", 10000.0f, +2.8f, 0.97f),
    AutoEqFilter("PK", 3076.0f, -2.1f, 3.52f),
    AutoEqFilter("PK", 237.0f, -0.5f, 1.04f),
    AutoEqFilter("PK", 1479.0f, -1.1f, 3.10f),
    AutoEqFilter("HS", 10000.0f, -8.7f, 0.70f),
    AutoEqFilter("PK", 9596.0f, +3.9f, 2.54f),
    AutoEqFilter("PK", 863.0f, +0.8f, 2.44f),
    AutoEqFilter("PK", 451.0f, -0.2f, 1.54f),
    AutoEqFilter("PK", 6947.0f, +1.4f, 5.99f),
)),
        AutoEqPreset("Moondrop Kato", "Moondrop", "in-ear", -3.2f, listOf(
    AutoEqFilter("LS", 105.0f, +3.2f, 0.70f),
    AutoEqFilter("PK", 728.0f, +2.7f, 1.40f),
    AutoEqFilter("PK", 1292.0f, -2.0f, 1.48f),
    AutoEqFilter("PK", 163.0f, -1.5f, 1.15f),
    AutoEqFilter("PK", 493.0f, +0.7f, 1.06f),
    AutoEqFilter("HS", 10000.0f, -4.7f, 0.70f),
    AutoEqFilter("PK", 9385.0f, +1.4f, 4.15f),
    AutoEqFilter("PK", 5710.0f, +1.0f, 2.88f),
    AutoEqFilter("PK", 9354.0f, -0.6f, 6.00f),
    AutoEqFilter("PK", 3127.0f, -0.3f, 3.89f),
)),

        // =========== Truthear (Chi-Fi) ===========
        AutoEqPreset("Truthear HEXA", "Truthear", "in-ear", -3.2f, listOf(
    AutoEqFilter("LS", 105.0f, -0.1f, 0.70f),
    AutoEqFilter("PK", 4844.0f, +3.4f, 1.81f),
    AutoEqFilter("PK", 199.0f, -1.9f, 1.54f),
    AutoEqFilter("PK", 62.0f, +1.6f, 1.05f),
    AutoEqFilter("PK", 3160.0f, -2.1f, 3.72f),
    AutoEqFilter("HS", 10000.0f, -4.4f, 0.70f),
    AutoEqFilter("PK", 707.0f, +1.0f, 1.89f),
    AutoEqFilter("PK", 6318.0f, +1.6f, 5.54f),
    AutoEqFilter("PK", 349.0f, -0.5f, 2.22f),
    AutoEqFilter("PK", 2113.0f, +0.6f, 3.41f),
)),
        AutoEqPreset("Truthear Zero:Red", "Truthear", "in-ear", -2.1f, listOf(
    AutoEqFilter("LS", 105.0f, -0.5f, 0.70f),
    AutoEqFilter("PK", 6163.0f, +2.2f, 2.44f),
    AutoEqFilter("PK", 341.0f, -1.0f, 1.46f),
    AutoEqFilter("PK", 70.0f, +1.0f, 0.61f),
    AutoEqFilter("PK", 1284.0f, -0.6f, 2.04f),
    AutoEqFilter("HS", 10000.0f, -1.8f, 0.70f),
    AutoEqFilter("PK", 2432.0f, +0.7f, 2.98f),
    AutoEqFilter("PK", 3588.0f, -0.6f, 3.06f),
    AutoEqFilter("PK", 8541.0f, +1.1f, 3.84f),
    AutoEqFilter("PK", 769.0f, +0.2f, 3.01f),
)),

        // =========== 7Hz (Chi-Fi) ===========
        AutoEqPreset("7Hz Timeless AE", "7Hz", "in-ear", -2.6f, listOf(
    AutoEqFilter("LS", 105.0f, -3.0f, 0.70f),
    AutoEqFilter("PK", 486.0f, +2.6f, 1.19f),
    AutoEqFilter("PK", 205.0f, -2.0f, 2.38f),
    AutoEqFilter("PK", 10000.0f, -3.3f, 2.54f),
    AutoEqFilter("PK", 2015.0f, -2.7f, 3.30f),
    AutoEqFilter("HS", 10000.0f, +1.6f, 0.70f),
    AutoEqFilter("PK", 3551.0f, +3.1f, 2.83f),
    AutoEqFilter("PK", 5659.0f, -1.9f, 2.33f),
    AutoEqFilter("PK", 72.0f, +0.6f, 1.72f),
    AutoEqFilter("PK", 1317.0f, -1.3f, 3.56f),
)),

        // =========== Google ===========
        AutoEqPreset("Pixel Buds Pro 2", "Google", "in-ear", -5.4f, listOf(
    AutoEqFilter("LS", 105.0f, -1.5f, 0.70f),
    AutoEqFilter("PK", 688.0f, +3.7f, 1.07f),
    AutoEqFilter("PK", 319.0f, -2.0f, 0.92f),
    AutoEqFilter("PK", 4748.0f, -6.0f, 6.00f),
    AutoEqFilter("PK", 8657.0f, -4.4f, 1.70f),
    AutoEqFilter("HS", 10000.0f, +5.5f, 0.70f),
    AutoEqFilter("PK", 3800.0f, +3.3f, 5.95f),
    AutoEqFilter("PK", 9591.0f, -2.6f, 4.15f),
    AutoEqFilter("PK", 6120.0f, -2.0f, 4.30f),
    AutoEqFilter("PK", 2469.0f, +0.9f, 4.61f),
)),

        // =========== Denon ===========
        AutoEqPreset("Denon AH-D5200", "Denon", "over-ear", -3.1f, listOf(
    AutoEqFilter("LS", 105.0f, +5.1f, 0.70f),
    AutoEqFilter("PK", 86.0f, -4.7f, 0.23f),
    AutoEqFilter("PK", 8914.0f, +2.5f, 2.81f),
    AutoEqFilter("PK", 2317.0f, +3.1f, 1.77f),
    AutoEqFilter("PK", 405.0f, +3.6f, 2.20f),
    AutoEqFilter("HS", 10000.0f, -0.6f, 0.70f),
    AutoEqFilter("PK", 5418.0f, -4.0f, 4.92f),
    AutoEqFilter("PK", 1225.0f, +2.0f, 6.00f),
    AutoEqFilter("PK", 6714.0f, +2.2f, 5.01f),
    AutoEqFilter("PK", 40.0f, -0.5f, 2.06f),
)),

        // =========== Edifier ===========
        AutoEqPreset("Edifier W820NB Plus", "Edifier", "over-ear", -3.4f, listOf(
    AutoEqFilter("LS", 105.0f, -1.1f, 0.70f),
    AutoEqFilter("PK", 340.0f, +3.5f, 1.94f),
    AutoEqFilter("PK", 2914.0f, -5.0f, 4.30f),
    AutoEqFilter("PK", 7052.0f, -3.7f, 2.22f),
    AutoEqFilter("PK", 4184.0f, +1.9f, 2.56f),
    AutoEqFilter("HS", 10000.0f, -2.0f, 0.70f),
    AutoEqFilter("PK", 1888.0f, +1.4f, 3.54f),
    AutoEqFilter("PK", 90.0f, +1.1f, 3.49f),
    AutoEqFilter("PK", 138.0f, -0.7f, 2.06f),
    AutoEqFilter("PK", 1218.0f, -1.1f, 4.47f),
)),

        // =========== QCY (budget Chinese brand) ===========
        AutoEqPreset("QCY MeloBuds Pro", "QCY", "in-ear", -6.5f, listOf(
    AutoEqFilter("LS", 105.0f, +15.8f, 0.70f),
    AutoEqFilter("PK", 45.0f, -17.2f, 0.31f),
    AutoEqFilter("PK", 223.0f, +6.7f, 1.28f),
    AutoEqFilter("PK", 543.0f, -2.3f, 1.95f),
    AutoEqFilter("PK", 828.0f, +2.2f, 1.15f),
    AutoEqFilter("HS", 10000.0f, +6.5f, 0.70f),
    AutoEqFilter("PK", 9004.0f, +3.7f, 2.34f),
    AutoEqFilter("PK", 5309.0f, -2.2f, 5.20f),
    AutoEqFilter("PK", 3051.0f, -1.6f, 3.29f),
    AutoEqFilter("PK", 2057.0f, +1.2f, 2.86f),
)),
    )

    /** 按品牌分组获取预设列表 */
    fun getPresetsByBrand(): Map<String, List<AutoEqPreset>> {
        return presets.groupBy { it.brand }
    }

    /** 获取所有品牌名称（排序） */
    fun getBrands(): List<String> {
        return presets.map { it.brand }.distinct().sorted()
    }

    /** 根据名称查找预设 */
    fun findByName(name: String): AutoEqPreset? {
        return presets.find { it.name == name }
    }

    /**
     * 将 AutoEq 预设应用到 DSP
     * @param player OboeDirectPlayer 实例
     * @param preset 选中的预设
     * @return true 成功, false 非 Oboe 模式跳过
     */
    fun applyPreset(player: OboeDirectPlayer, preset: AutoEqPreset): Boolean {
        if (!OboeDirectPlayer.nativeLibLoaded) return false

        val gainsDb = FloatArray(10)
        val freqsHz = FloatArray(10)
        val qValues = FloatArray(10)
        val filterTypes = IntArray(10)

        preset.filters.take(10).forEachIndexed { i, f ->
            gainsDb[i] = f.gain
            freqsHz[i] = f.freq
            qValues[i] = f.q.coerceIn(0.1f, 10.0f)
            filterTypes[i] = filterTypeInt(f.type)
        }

        player.setAutoEq10Band(gainsDb, freqsHz, qValues, filterTypes, preset.preamp)
        Log.i(TAG, "AutoEQ preset applied: ${preset.name} (${preset.brand})")
        return true
    }

    /** 清除 AutoEQ 修正，恢复默认 DSP */
    fun clearPreset(player: OboeDirectPlayer) {
        player.resetAutoEq()
        Log.i(TAG, "AutoEQ cleared, default DSP restored")
    }
}
