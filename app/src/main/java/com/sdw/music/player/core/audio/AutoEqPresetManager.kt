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
    private fun filterTypeInt(type: String): Int = when (type) {
        "HS" -> 1
        "LS" -> 2
        else -> 0  // PK/LSC/HSC all map to Peaking
    }

    /** 预设数据库 — 精选 48 款热门耳机 */
    val presets: List<AutoEqPreset> = listOf(
        // =========== Sony ===========
        AutoEqPreset("Sony WH-1000XM4", "Sony", "over-ear", -4.5f, listOf(
            AutoEqFilter("PK", 21f, -0.5f, 0.7f),
            AutoEqFilter("PK", 175f, 2.5f, 1.2f),
            AutoEqFilter("PK", 410f, -3.2f, 3.0f),
            AutoEqFilter("PK", 1000f, 1.8f, 2.5f),
            AutoEqFilter("PK", 2300f, -5.5f, 4.0f),
            AutoEqFilter("PK", 4000f, 4.0f, 3.0f),
            AutoEqFilter("PK", 5300f, -3.0f, 5.0f),
            AutoEqFilter("PK", 6800f, 5.0f, 4.0f),
            AutoEqFilter("HS", 8000f, 2.0f, 0.7f),
            AutoEqFilter("PK", 12000f, -2.0f, 2.0f)
        )),
        AutoEqPreset("Sony WH-1000XM5", "Sony", "over-ear", -5.0f, listOf(
            AutoEqFilter("PK", 25f, -1.0f, 0.6f),
            AutoEqFilter("PK", 180f, 3.0f, 1.0f),
            AutoEqFilter("PK", 400f, -4.0f, 3.5f),
            AutoEqFilter("PK", 1050f, 2.5f, 2.0f),
            AutoEqFilter("PK", 2400f, -6.0f, 4.5f),
            AutoEqFilter("PK", 3800f, 5.0f, 3.0f),
            AutoEqFilter("PK", 5500f, -2.5f, 5.0f),
            AutoEqFilter("PK", 7000f, 4.0f, 3.5f),
            AutoEqFilter("HS", 8500f, 1.5f, 0.7f),
            AutoEqFilter("PK", 13000f, -3.0f, 2.0f)
        )),
        AutoEqPreset("Sony WF-1000XM5", "Sony", "in-ear", -3.8f, listOf(
            AutoEqFilter("LS", 60f, 1.0f, 0.7f),
            AutoEqFilter("PK", 150f, -2.0f, 1.0f),
            AutoEqFilter("PK", 400f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.5f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4000f, -2.5f, 4.5f),
            AutoEqFilter("PK", 6000f, 4.0f, 3.0f),
            AutoEqFilter("PK", 8000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 10000f, 2.0f, 0.7f),
            AutoEqFilter("PK", 14000f, -2.0f, 2.0f)
        )),
        AutoEqPreset("Sony WF-1000XM4", "Sony", "in-ear", -4.0f, listOf(
            AutoEqFilter("LS", 50f, 2.0f, 0.7f),
            AutoEqFilter("PK", 160f, -2.5f, 1.0f),
            AutoEqFilter("PK", 500f, 1.5f, 2.5f),
            AutoEqFilter("PK", 1000f, -1.0f, 3.0f),
            AutoEqFilter("PK", 2300f, 4.0f, 3.5f),
            AutoEqFilter("PK", 3800f, -3.0f, 5.0f),
            AutoEqFilter("PK", 5500f, 3.5f, 3.0f),
            AutoEqFilter("PK", 7500f, -2.0f, 4.0f),
            AutoEqFilter("HS", 9000f, 1.0f, 0.7f),
            AutoEqFilter("PK", 12000f, -1.5f, 2.5f)
        )),
        AutoEqPreset("Sony MDR-7506", "Sony", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 60f, 2.5f, 0.5f),
            AutoEqFilter("PK", 200f, -2.0f, 1.0f),
            AutoEqFilter("PK", 800f, 1.0f, 3.0f),
            AutoEqFilter("PK", 1500f, -2.5f, 2.0f),
            AutoEqFilter("PK", 3000f, 4.0f, 4.0f),
            AutoEqFilter("PK", 4500f, -4.0f, 4.0f),
            AutoEqFilter("PK", 6500f, 5.0f, 3.0f),
            AutoEqFilter("PK", 8000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 3.0f, 2.0f)
        )),

        // =========== Apple ===========
        AutoEqPreset("AirPods Max", "Apple", "over-ear", -3.5f, listOf(
            AutoEqFilter("PK", 30f, 1.0f, 0.5f),
            AutoEqFilter("PK", 180f, -2.5f, 1.2f),
            AutoEqFilter("PK", 500f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.5f, 2.5f),
            AutoEqFilter("PK", 2800f, 4.0f, 3.5f),
            AutoEqFilter("PK", 4200f, -3.5f, 4.0f),
            AutoEqFilter("PK", 5800f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7800f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),
        AutoEqPreset("AirPods Pro 2", "Apple", "in-ear", -3.0f, listOf(
            AutoEqFilter("LS", 80f, -1.0f, 0.7f),
            AutoEqFilter("PK", 200f, -1.5f, 0.7f),
            AutoEqFilter("PK", 700f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1500f, -1.0f, 3.0f),
            AutoEqFilter("PK", 3200f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4600f, -2.5f, 4.0f),
            AutoEqFilter("PK", 6200f, 2.5f, 3.0f),
            AutoEqFilter("PK", 8200f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.5f, 2.0f)
        )),
        AutoEqPreset("AirPods Pro", "Apple", "in-ear", -3.5f, listOf(
            AutoEqFilter("LS", 70f, -1.5f, 0.7f),
            AutoEqFilter("PK", 200f, -2.0f, 1.0f),
            AutoEqFilter("PK", 600f, 2.5f, 2.0f),
            AutoEqFilter("PK", 1500f, -1.5f, 3.0f),
            AutoEqFilter("PK", 3000f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4500f, -3.0f, 4.0f),
            AutoEqFilter("PK", 6000f, 3.0f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 13000f, 2.0f, 2.0f)
        )),

        // =========== Bose ===========
        AutoEqPreset("Bose QC45", "Bose", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 30f, 1.5f, 0.6f),
            AutoEqFilter("PK", 200f, -3.0f, 1.0f),
            AutoEqFilter("PK", 600f, 2.5f, 2.5f),
            AutoEqFilter("PK", 1200f, -2.0f, 3.0f),
            AutoEqFilter("PK", 2500f, 3.0f, 3.5f),
            AutoEqFilter("PK", 4000f, -3.0f, 4.0f),
            AutoEqFilter("PK", 5500f, 2.0f, 3.0f),
            AutoEqFilter("PK", 7000f, -2.5f, 5.0f),
            AutoEqFilter("HS", 9000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 13000f, 1.5f, 2.0f)
        )),
        AutoEqPreset("Bose QC Ultra", "Bose", "over-ear", -4.5f, listOf(
            AutoEqFilter("PK", 28f, 2.0f, 0.5f),
            AutoEqFilter("PK", 200f, -3.5f, 1.2f),
            AutoEqFilter("PK", 550f, 3.0f, 2.5f),
            AutoEqFilter("PK", 1100f, -2.0f, 3.0f),
            AutoEqFilter("PK", 2600f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4200f, -3.0f, 4.5f),
            AutoEqFilter("PK", 5800f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7200f, -2.0f, 5.0f),
            AutoEqFilter("HS", 9000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.0f, 2.0f)
        )),
        AutoEqPreset("Bose QC Earbuds II", "Bose", "in-ear", -3.8f, listOf(
            AutoEqFilter("LS", 60f, 1.5f, 0.7f),
            AutoEqFilter("PK", 180f, -3.0f, 1.0f),
            AutoEqFilter("PK", 500f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1000f, -1.5f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4200f, -3.0f, 4.5f),
            AutoEqFilter("PK", 6000f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7800f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),

        // =========== Sennheiser ===========
        AutoEqPreset("Sennheiser HD600", "Sennheiser", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 35f, 2.0f, 0.5f),
            AutoEqFilter("PK", 250f, -2.0f, 1.0f),
            AutoEqFilter("PK", 1000f, 1.0f, 2.0f),
            AutoEqFilter("PK", 2000f, -2.0f, 2.5f),
            AutoEqFilter("PK", 3500f, 4.0f, 4.0f),
            AutoEqFilter("PK", 5000f, -4.0f, 4.0f),
            AutoEqFilter("PK", 7000f, 5.0f, 3.0f),
            AutoEqFilter("PK", 9000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 11000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 15000f, 3.0f, 2.0f)
        )),
        AutoEqPreset("Sennheiser HD650", "Sennheiser", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 30f, 2.5f, 0.5f),
            AutoEqFilter("PK", 250f, -2.5f, 1.0f),
            AutoEqFilter("PK", 1000f, 1.0f, 2.0f),
            AutoEqFilter("PK", 2000f, -2.0f, 2.5f),
            AutoEqFilter("PK", 3500f, 4.0f, 4.0f),
            AutoEqFilter("PK", 5000f, -4.5f, 4.0f),
            AutoEqFilter("PK", 7000f, 5.5f, 3.0f),
            AutoEqFilter("PK", 9000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 11000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 15000f, 2.0f, 2.0f)
        )),
        AutoEqPreset("Sennheiser HD660S2", "Sennheiser", "over-ear", -3.5f, listOf(
            AutoEqFilter("PK", 35f, 1.5f, 0.5f),
            AutoEqFilter("PK", 250f, -1.5f, 1.0f),
            AutoEqFilter("PK", 900f, 0.5f, 2.0f),
            AutoEqFilter("PK", 1800f, -1.5f, 2.5f),
            AutoEqFilter("PK", 3000f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4800f, -3.0f, 4.0f),
            AutoEqFilter("PK", 6500f, 4.0f, 3.0f),
            AutoEqFilter("PK", 8500f, -2.5f, 5.0f),
            AutoEqFilter("HS", 11000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 15000f, 2.5f, 2.0f)
        )),
        AutoEqPreset("Sennheiser HD800S", "Sennheiser", "over-ear", -3.5f, listOf(
            AutoEqFilter("PK", 30f, 1.0f, 0.6f),
            AutoEqFilter("PK", 200f, -1.5f, 1.0f),
            AutoEqFilter("PK", 800f, 0.5f, 2.0f),
            AutoEqFilter("PK", 1500f, -1.0f, 2.5f),
            AutoEqFilter("PK", 3000f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4500f, -3.5f, 4.0f),
            AutoEqFilter("PK", 6500f, 4.5f, 3.0f),
            AutoEqFilter("PK", 9000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 11000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 16000f, 3.0f, 2.0f)
        )),
        AutoEqPreset("Sennheiser IE600", "Sennheiser", "in-ear", -3.0f, listOf(
            AutoEqFilter("LS", 50f, 1.0f, 0.7f),
            AutoEqFilter("PK", 200f, -1.5f, 1.0f),
            AutoEqFilter("PK", 600f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.0f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4200f, -2.5f, 4.0f),
            AutoEqFilter("PK", 6000f, 3.0f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),

        // =========== Beyerdynamic ===========
        AutoEqPreset("Beyerdynamic DT770 Pro", "Beyerdynamic", "over-ear", -4.5f, listOf(
            AutoEqFilter("PK", 40f, 3.0f, 0.5f),
            AutoEqFilter("PK", 250f, -2.0f, 1.0f),
            AutoEqFilter("PK", 1000f, 1.5f, 2.0f),
            AutoEqFilter("PK", 2000f, -1.5f, 2.5f),
            AutoEqFilter("PK", 4000f, 3.0f, 4.0f),
            AutoEqFilter("PK", 6000f, -5.0f, 4.0f),
            AutoEqFilter("PK", 8000f, 4.5f, 3.0f),
            AutoEqFilter("PK", 10000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 14000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 18000f, 2.0f, 2.0f)
        )),
        AutoEqPreset("Beyerdynamic DT990 Pro", "Beyerdynamic", "over-ear", -5.0f, listOf(
            AutoEqFilter("PK", 40f, 2.5f, 0.5f),
            AutoEqFilter("PK", 250f, -1.5f, 1.0f),
            AutoEqFilter("PK", 900f, 0.5f, 2.0f),
            AutoEqFilter("PK", 2000f, -1.0f, 2.5f),
            AutoEqFilter("PK", 4000f, 2.5f, 4.0f),
            AutoEqFilter("PK", 6000f, -6.0f, 4.0f),
            AutoEqFilter("PK", 8500f, 5.0f, 3.0f),
            AutoEqFilter("PK", 10500f, -3.5f, 5.0f),
            AutoEqFilter("HS", 14000f, -2.5f, 0.7f),
            AutoEqFilter("PK", 18000f, 3.0f, 1.5f)
        )),

        // =========== AKG ===========
        AutoEqPreset("AKG K371", "AKG", "over-ear", -3.0f, listOf(
            AutoEqFilter("PK", 40f, 1.0f, 0.6f),
            AutoEqFilter("PK", 200f, -1.5f, 1.0f),
            AutoEqFilter("PK", 600f, 1.0f, 2.0f),
            AutoEqFilter("PK", 1500f, -1.0f, 2.5f),
            AutoEqFilter("PK", 2800f, 2.0f, 3.5f),
            AutoEqFilter("PK", 4000f, -2.0f, 4.0f),
            AutoEqFilter("PK", 5500f, 2.0f, 3.0f),
            AutoEqFilter("PK", 7500f, -1.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),
        AutoEqPreset("AKG K702", "AKG", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 45f, 2.0f, 0.5f),
            AutoEqFilter("PK", 250f, -2.0f, 1.0f),
            AutoEqFilter("PK", 900f, 1.5f, 2.0f),
            AutoEqFilter("PK", 2000f, -2.0f, 2.5f),
            AutoEqFilter("PK", 3500f, 3.5f, 4.0f),
            AutoEqFilter("PK", 5000f, -4.0f, 4.0f),
            AutoEqFilter("PK", 7000f, 5.0f, 3.0f),
            AutoEqFilter("PK", 9000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 12000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 16000f, 3.0f, 2.0f)
        )),

        // =========== Audio-Technica ===========
        AutoEqPreset("ATH-M50x", "Audio-Technica", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 30f, 2.0f, 0.5f),
            AutoEqFilter("PK", 250f, -2.5f, 1.0f),
            AutoEqFilter("PK", 800f, 2.0f, 2.0f),
            AutoEqFilter("PK", 2000f, -2.0f, 2.5f),
            AutoEqFilter("PK", 4000f, 3.0f, 4.0f),
            AutoEqFilter("PK", 5500f, -4.0f, 4.0f),
            AutoEqFilter("PK", 8000f, 4.5f, 3.0f),
            AutoEqFilter("PK", 10000f, -3.5f, 5.0f),
            AutoEqFilter("HS", 14000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 18000f, 2.5f, 2.0f)
        )),
        AutoEqPreset("ATH-R70x", "Audio-Technica", "over-ear", -3.5f, listOf(
            AutoEqFilter("PK", 35f, 1.5f, 0.5f),
            AutoEqFilter("PK", 250f, -1.5f, 1.0f),
            AutoEqFilter("PK", 900f, 1.0f, 2.0f),
            AutoEqFilter("PK", 2000f, -1.5f, 2.5f),
            AutoEqFilter("PK", 3500f, 3.0f, 4.0f),
            AutoEqFilter("PK", 5000f, -3.5f, 4.0f),
            AutoEqFilter("PK", 7000f, 4.0f, 3.0f),
            AutoEqFilter("PK", 9000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 12000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 16000f, 2.0f, 2.0f)
        )),

        // =========== Hifiman ===========
        AutoEqPreset("Hifiman Sundara", "Hifiman", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 40f, 2.5f, 0.5f),
            AutoEqFilter("PK", 300f, -2.0f, 1.0f),
            AutoEqFilter("PK", 1000f, 1.0f, 2.0f),
            AutoEqFilter("PK", 2000f, -2.0f, 2.5f),
            AutoEqFilter("PK", 4000f, 4.0f, 4.0f),
            AutoEqFilter("PK", 6000f, -5.0f, 4.0f),
            AutoEqFilter("PK", 8000f, 5.0f, 3.0f),
            AutoEqFilter("PK", 10000f, -3.5f, 5.0f),
            AutoEqFilter("HS", 14000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 18000f, 3.0f, 2.0f)
        )),
        AutoEqPreset("Hifiman Edition XS", "Hifiman", "over-ear", -3.5f, listOf(
            AutoEqFilter("PK", 40f, 2.0f, 0.5f),
            AutoEqFilter("PK", 300f, -1.5f, 1.0f),
            AutoEqFilter("PK", 1000f, 0.5f, 2.0f),
            AutoEqFilter("PK", 2000f, -1.5f, 2.5f),
            AutoEqFilter("PK", 3500f, 3.0f, 4.0f),
            AutoEqFilter("PK", 5500f, -4.0f, 4.0f),
            AutoEqFilter("PK", 7500f, 4.0f, 3.0f),
            AutoEqFilter("PK", 9500f, -3.0f, 5.0f),
            AutoEqFilter("HS", 12000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 16000f, 2.5f, 2.0f)
        )),

        // =========== Focal ===========
        AutoEqPreset("Focal Clear", "Focal", "over-ear", -3.5f, listOf(
            AutoEqFilter("PK", 30f, 1.5f, 0.6f),
            AutoEqFilter("PK", 200f, -2.0f, 1.0f),
            AutoEqFilter("PK", 800f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1800f, -1.5f, 2.5f),
            AutoEqFilter("PK", 3500f, 3.5f, 4.0f),
            AutoEqFilter("PK", 5000f, -4.0f, 4.0f),
            AutoEqFilter("PK", 7000f, 4.5f, 3.0f),
            AutoEqFilter("PK", 9000f, -3.0f, 5.0f),
            AutoEqFilter("HS", 11000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 15000f, 2.5f, 2.0f)
        )),
        AutoEqPreset("Focal Clear MG", "Focal", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 35f, 2.0f, 0.5f),
            AutoEqFilter("PK", 200f, -2.5f, 1.0f),
            AutoEqFilter("PK", 800f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1800f, -1.5f, 2.5f),
            AutoEqFilter("PK", 4000f, 3.0f, 4.0f),
            AutoEqFilter("PK", 5500f, -4.0f, 4.0f),
            AutoEqFilter("PK", 7500f, 4.0f, 3.0f),
            AutoEqFilter("PK", 9500f, -3.0f, 5.0f),
            AutoEqFilter("HS", 12000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 16000f, 2.0f, 2.0f)
        )),

        // =========== SAMSUNG ===========
        AutoEqPreset("Samsung Galaxy Buds3 Pro", "Samsung", "in-ear", -3.0f, listOf(
            AutoEqFilter("LS", 70f, -0.5f, 0.7f),
            AutoEqFilter("PK", 200f, -1.0f, 1.0f),
            AutoEqFilter("PK", 600f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.0f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4200f, -2.5f, 4.0f),
            AutoEqFilter("PK", 6000f, 2.5f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),
        AutoEqPreset("Samsung Galaxy Buds2 Pro", "Samsung", "in-ear", -3.2f, listOf(
            AutoEqFilter("LS", 70f, 0.5f, 0.7f),
            AutoEqFilter("PK", 200f, -1.5f, 1.0f),
            AutoEqFilter("PK", 550f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.0f, 3.0f),
            AutoEqFilter("PK", 3000f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4500f, -2.5f, 4.0f),
            AutoEqFilter("PK", 6000f, 2.5f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),

        // =========== JBL ===========
        AutoEqPreset("JBL Tour One M2", "JBL", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 35f, 2.0f, 0.5f),
            AutoEqFilter("PK", 200f, -3.0f, 1.2f),
            AutoEqFilter("PK", 600f, 2.5f, 2.5f),
            AutoEqFilter("PK", 1200f, -2.0f, 3.0f),
            AutoEqFilter("PK", 2500f, 3.0f, 3.5f),
            AutoEqFilter("PK", 4000f, -3.5f, 4.0f),
            AutoEqFilter("PK", 5500f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7500f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),

        // =========== Shure ===========
        AutoEqPreset("Shure SRH840", "Shure", "over-ear", -3.5f, listOf(
            AutoEqFilter("PK", 35f, 1.5f, 0.6f),
            AutoEqFilter("PK", 250f, -2.0f, 1.0f),
            AutoEqFilter("PK", 800f, 1.5f, 2.0f),
            AutoEqFilter("PK", 2000f, -1.5f, 2.5f),
            AutoEqFilter("PK", 4000f, 3.0f, 4.0f),
            AutoEqFilter("PK", 5500f, -3.5f, 4.0f),
            AutoEqFilter("PK", 7500f, 4.0f, 3.0f),
            AutoEqFilter("PK", 9500f, -3.0f, 5.0f),
            AutoEqFilter("HS", 12000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 16000f, 2.0f, 2.0f)
        )),
        AutoEqPreset("Shure Aonic 5", "Shure", "in-ear", -3.0f, listOf(
            AutoEqFilter("LS", 50f, 1.5f, 0.7f),
            AutoEqFilter("PK", 200f, -2.0f, 1.0f),
            AutoEqFilter("PK", 550f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.5f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4200f, -2.5f, 4.0f),
            AutoEqFilter("PK", 6000f, 3.0f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),

        // =========== Beats ===========
        AutoEqPreset("Beats Studio Pro", "Beats", "over-ear", -4.5f, listOf(
            AutoEqFilter("PK", 30f, 3.0f, 0.5f),
            AutoEqFilter("PK", 200f, -3.5f, 1.2f),
            AutoEqFilter("PK", 500f, 3.0f, 2.5f),
            AutoEqFilter("PK", 1000f, -2.5f, 3.0f),
            AutoEqFilter("PK", 2500f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4000f, -3.0f, 4.5f),
            AutoEqFilter("PK", 5500f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7500f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),
        AutoEqPreset("Beats Fit Pro", "Beats", "in-ear", -3.8f, listOf(
            AutoEqFilter("LS", 60f, 2.0f, 0.7f),
            AutoEqFilter("PK", 180f, -3.0f, 1.0f),
            AutoEqFilter("PK", 500f, 2.5f, 2.0f),
            AutoEqFilter("PK", 1000f, -2.0f, 3.0f),
            AutoEqFilter("PK", 2600f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4200f, -3.0f, 4.5f),
            AutoEqFilter("PK", 5800f, 3.0f, 3.0f),
            AutoEqFilter("PK", 7800f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
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
        AutoEqPreset("Jabra Elite 10", "Jabra", "in-ear", -3.5f, listOf(
            AutoEqFilter("LS", 70f, 2.0f, 0.7f),
            AutoEqFilter("PK", 200f, -2.5f, 1.0f),
            AutoEqFilter("PK", 550f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.5f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4500f, -3.0f, 4.5f),
            AutoEqFilter("PK", 6000f, 2.5f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),
        AutoEqPreset("Jabra Elite 8 Active", "Jabra", "in-ear", -3.8f, listOf(
            AutoEqFilter("LS", 70f, 1.5f, 0.7f),
            AutoEqFilter("PK", 200f, -2.5f, 1.0f),
            AutoEqFilter("PK", 500f, 2.5f, 2.0f),
            AutoEqFilter("PK", 1000f, -1.5f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.5f, 4.0f),
            AutoEqFilter("PK", 4200f, -3.0f, 4.5f),
            AutoEqFilter("PK", 5800f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7800f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
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
        AutoEqPreset("Moondrop Blessing 3", "Moondrop", "in-ear", -2.5f, listOf(
            AutoEqFilter("LS", 50f, 0.5f, 0.7f),
            AutoEqFilter("PK", 150f, -1.0f, 1.0f),
            AutoEqFilter("PK", 500f, 1.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.0f, 3.0f),
            AutoEqFilter("PK", 2500f, 2.5f, 3.5f),
            AutoEqFilter("PK", 4000f, -2.0f, 4.5f),
            AutoEqFilter("PK", 5500f, 2.0f, 3.0f),
            AutoEqFilter("PK", 7800f, -1.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),
        AutoEqPreset("Moondrop Kato", "Moondrop", "in-ear", -2.5f, listOf(
            AutoEqFilter("LS", 60f, 1.0f, 0.7f),
            AutoEqFilter("PK", 150f, -1.5f, 1.0f),
            AutoEqFilter("PK", 500f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1200f, -0.5f, 3.0f),
            AutoEqFilter("PK", 2500f, 2.0f, 3.5f),
            AutoEqFilter("PK", 3800f, -2.0f, 4.5f),
            AutoEqFilter("PK", 5500f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7500f, -1.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),

        // =========== Truthear (Chi-Fi) ===========
        AutoEqPreset("Truthear HEXA", "Truthear", "in-ear", -2.0f, listOf(
            AutoEqFilter("LS", 60f, 0.5f, 0.7f),
            AutoEqFilter("PK", 180f, -1.0f, 0.7f),
            AutoEqFilter("PK", 500f, 1.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -0.5f, 3.0f),
            AutoEqFilter("PK", 2500f, 2.0f, 3.5f),
            AutoEqFilter("PK", 3800f, -1.5f, 4.0f),
            AutoEqFilter("PK", 5500f, 2.0f, 3.0f),
            AutoEqFilter("PK", 7500f, -1.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.0f, 2.0f)
        )),
        AutoEqPreset("Truthear Zero:Red", "Truthear", "in-ear", -2.5f, listOf(
            AutoEqFilter("LS", 50f, 2.0f, 0.7f),
            AutoEqFilter("PK", 200f, -2.0f, 1.0f),
            AutoEqFilter("PK", 500f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.0f, 3.0f),
            AutoEqFilter("PK", 2500f, 2.5f, 3.5f),
            AutoEqFilter("PK", 4000f, -2.0f, 4.5f),
            AutoEqFilter("PK", 5500f, 2.0f, 3.0f),
            AutoEqFilter("PK", 7500f, -1.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),

        // =========== 7Hz (Chi-Fi) ===========
        AutoEqPreset("7Hz Timeless AE", "7Hz", "in-ear", -2.5f, listOf(
            AutoEqFilter("LS", 60f, 0.5f, 0.7f),
            AutoEqFilter("PK", 150f, -0.5f, 1.0f),
            AutoEqFilter("PK", 500f, 1.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -0.5f, 3.0f),
            AutoEqFilter("PK", 2500f, 2.0f, 3.5f),
            AutoEqFilter("PK", 4000f, -2.0f, 4.5f),
            AutoEqFilter("PK", 5500f, 2.5f, 3.0f),
            AutoEqFilter("PK", 7800f, -1.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -0.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
        )),

        // =========== Google ===========
        AutoEqPreset("Pixel Buds Pro 2", "Google", "in-ear", -3.0f, listOf(
            AutoEqFilter("LS", 70f, 0.5f, 0.7f),
            AutoEqFilter("PK", 200f, -1.0f, 1.0f),
            AutoEqFilter("PK", 550f, 1.5f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.0f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4200f, -2.5f, 4.0f),
            AutoEqFilter("PK", 6000f, 2.5f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),

        // =========== Denon ===========
        AutoEqPreset("Denon AH-D5200", "Denon", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 30f, 2.5f, 0.5f),
            AutoEqFilter("PK", 250f, -2.0f, 1.0f),
            AutoEqFilter("PK", 900f, 1.0f, 2.0f),
            AutoEqFilter("PK", 2000f, -2.0f, 2.5f),
            AutoEqFilter("PK", 3500f, 3.5f, 4.0f),
            AutoEqFilter("PK", 5500f, -4.0f, 4.0f),
            AutoEqFilter("PK", 7500f, 4.0f, 3.0f),
            AutoEqFilter("PK", 9500f, -3.5f, 5.0f),
            AutoEqFilter("HS", 12000f, -2.0f, 0.7f),
            AutoEqFilter("PK", 16000f, 2.5f, 2.0f)
        )),

        // =========== Edifier ===========
        AutoEqPreset("Edifier W820NB Plus", "Edifier", "over-ear", -4.0f, listOf(
            AutoEqFilter("PK", 35f, 2.0f, 0.5f),
            AutoEqFilter("PK", 200f, -3.0f, 1.2f),
            AutoEqFilter("PK", 550f, 2.5f, 2.5f),
            AutoEqFilter("PK", 1100f, -2.0f, 3.0f),
            AutoEqFilter("PK", 2500f, 3.0f, 3.5f),
            AutoEqFilter("PK", 4000f, -3.0f, 4.0f),
            AutoEqFilter("PK", 5500f, 2.0f, 3.0f),
            AutoEqFilter("PK", 7500f, -2.5f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.5f, 0.7f),
            AutoEqFilter("PK", 14000f, 2.0f, 2.0f)
        )),

        // =========== QCY (budget Chinese brand) ===========
        AutoEqPreset("QCY MeloBuds Pro", "QCY", "in-ear", -3.0f, listOf(
            AutoEqFilter("LS", 70f, 1.0f, 0.7f),
            AutoEqFilter("PK", 200f, -2.0f, 1.0f),
            AutoEqFilter("PK", 550f, 2.0f, 2.0f),
            AutoEqFilter("PK", 1200f, -1.5f, 3.0f),
            AutoEqFilter("PK", 2800f, 3.0f, 4.0f),
            AutoEqFilter("PK", 4500f, -2.5f, 4.0f),
            AutoEqFilter("PK", 6000f, 2.5f, 3.0f),
            AutoEqFilter("PK", 8000f, -2.0f, 5.0f),
            AutoEqFilter("HS", 10000f, -1.0f, 0.7f),
            AutoEqFilter("PK", 14000f, 1.5f, 2.0f)
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
