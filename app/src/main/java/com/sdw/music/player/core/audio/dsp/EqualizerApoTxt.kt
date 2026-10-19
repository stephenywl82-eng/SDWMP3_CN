package com.sdw.music.player.core.audio.dsp

import android.util.Log

/**
 * EqualizerAPO 文本格式 导入/导出。
 *
 * 支持两种官方格式：
 *  1) Filter（参数，无损）：
 *       Filter: ON PK Fc 1000 Hz Gain 3 dB Q 1.0
 *       Filter: ON LS Fc 105 Hz Gain -4.2 dB Q 0.7
 *     （PK=Peaking, LS=LowShelf, HS=HighShelf，与本 App 三段类型一一对应）
 *  2) GraphicEQ（图形，近似）：
 *       GraphicEQ: 25 -3.2; 40 0; 60 2.1; ...
 *     仅 频率/增益 两点列，无 Q/类型 → 每个频点当作一个 Peaking 段（Q 取 1.0）。
 *
 * 导入时统一夹到本 App 区间：freq∈[20,20000]，gain∈[-15,15]，Q∈[0.1,18]。
 * 段数上限取 ParametricEqManager.MAX_BANDS（=64），多余截断。
 */
object EqualizerApoTxt {
    private const val TAG = "EqualizerApoTxt"

    // 兼容两种写法：本 App 导出的 `Filter:` 与 AutoEQ 数据库的 `Filter 1:`（带序号）
    private val FILTER_RE = Regex("(?i)^filter\\s*\\d*\\s*:")

    data class ParsedEq(
        val name: String,
        val preamp: Float,
        val bands: List<ParametricEqManager.Band>
    ) {
        fun toPreset(): ParametricEqManager.Preset =
            ParametricEqManager.Preset(name = name, preamp = preamp, bands = bands)
    }

    /** 解析 EqualizerAPO 文本；找不到任何有效段时返回 null。 */
    fun parse(text: String): ParsedEq? {
        val filters = mutableListOf<ParametricEqManager.Band>()
        var preamp = 0f
        var hasFilter = false
        var hasGraphic = false
        var expectGraphic = false   // 遇到 GraphicEQ 头后，后续纯数字行视为图形点（多行写法）

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                FILTER_RE.containsMatchIn(line) -> {
                    parseFilterLine(line)?.let { filters.add(it); hasFilter = true }
                    expectGraphic = false
                }
                line.startsWith("Preamp:", ignoreCase = true) -> {
                    parsePreamp(line)?.let { preamp = it }
                    expectGraphic = false
                }
                line.startsWith("GraphicEQ:", ignoreCase = true) -> {
                    val pts = parseGraphicInline(line)
                    if (pts.isNotEmpty()) { filters.addAll(pts); hasGraphic = true }
                    expectGraphic = true
                }
                expectGraphic && isTwoNumbers(line) -> {
                    parseGraphicPoint(line)?.let { filters.add(it); hasGraphic = true }
                }
            }
        }

        if (!hasFilter && !hasGraphic) return null
        val trimmed = filters.take(ParametricEqManager.MAX_BANDS)
        return ParsedEq(name = "Imported EQ", preamp = preamp, bands = trimmed)
    }

    private fun parseFilterLine(line: String): ParametricEqManager.Band? {
        // 兼容两种写法：
        //   本 App：     Filter: ON PK Fc 1000 Hz Gain 3 dB Q 1.0
        //   AutoEQ 库：  Filter 1: ON LSC Fc 105 Hz Gain 2.6 dB Q 0.70
        val tokens = line.split(Regex("\\s+"))
        // 启用状态：出现 OFF 即禁用，否则视为启用（ON 或默认）
        val enabled = !tokens.any { it.equals("OFF", ignoreCase = true) }
        // 类型：在 token 中查找，兼容 PK/PEAKING、LS/LSC/LOWSHELF、HS/HSC/HIGHSHELF
        var type: Int? = null
        for (t in tokens) {
            when (t.uppercase()) {
                "PK", "PEAKING", "PEAK" -> type = ParametricEqManager.TYPE_PEAKING
                "LS", "LSC", "LOWSHELF", "LOW_SHELF" -> type = ParametricEqManager.TYPE_LOW_SHELF
                "HS", "HSC", "HIGHSHELF", "HIGH_SHELF" -> type = ParametricEqManager.TYPE_HIGH_SHELF
            }
            if (type != null) break
        }
        if (type == null) return null
        var freq = 1000f
        var gain = 0f
        var q = 1.0f
        var i = 0
        while (i < tokens.size) {
            when (tokens[i].uppercase()) {
                "FC" -> { if (i + 1 < tokens.size) freq = tokens[i + 1].toFloatOrNull() ?: freq; i += 2 }
                "GAIN" -> { if (i + 1 < tokens.size) gain = tokens[i + 1].toFloatOrNull() ?: gain; i += 2 }
                "Q" -> { if (i + 1 < tokens.size) q = tokens[i + 1].toFloatOrNull() ?: q; i += 2 }
                else -> i += 1   // 跳过序号、单位（Hz/dB）等无关词
            }
        }
        if (freq <= 0f) return null
        return ParametricEqManager.Band(
            type = type,
            freq = freq.coerceIn(20f, 20000f),
            gain = gain.coerceIn(-15f, 15f),
            q = q.coerceIn(0.1f, 18f),
            enabled = enabled
        )
    }

    private fun parsePreamp(line: String): Float? {
        // Preamp: -6.1 dB
        val m = Regex("(-?\\d+(?:\\.\\d+)?)").find(line) ?: return null
        return m.value.toFloatOrNull()?.coerceIn(-15f, 0f)
    }

    private fun parseGraphicInline(line: String): List<ParametricEqManager.Band> {
        // GraphicEQ: 25 -3.2; 40 0; 60 2.1
        val body = line.removePrefix("GraphicEQ:").trim()
        return body.split(';').mapNotNull { parseGraphicPoint(it) }
    }

    private fun parseGraphicPoint(cell: String): ParametricEqManager.Band? {
        val nums = cell.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (nums.size < 2) return null
        val f = nums[0].toFloatOrNull() ?: return null
        val g = nums[1].toFloatOrNull() ?: return null
        if (f <= 0f) return null
        return ParametricEqManager.Band(
            type = ParametricEqManager.TYPE_PEAKING,
            freq = f.coerceIn(20f, 20000f),
            gain = g.coerceIn(-15f, 15f),
            q = 1.0f,
            enabled = true
        )
    }

    private fun isTwoNumbers(line: String): Boolean =
        Regex("^-?\\d+(?:\\.\\d+)?\\s+-?\\d+(?:\\.\\d+)?$").matches(line)

    /** 导出为 EqualizerAPO Filter 格式（无损、可逆）。仅导出启用段。 */
    fun toText(preset: ParametricEqManager.Preset): String {
        val sb = StringBuilder()
        sb.appendLine("; Exported from SDWMP3 PEQ")
        sb.appendLine("; ${preset.name}")
        if (preset.preamp < 0f) sb.appendLine("Preamp: ${"%.1f".format(preset.preamp)} dB")
        sb.appendLine()
        preset.bands.forEach { b ->
            if (!b.enabled) return@forEach
            val typeStr = when (b.type) {
                ParametricEqManager.TYPE_LOW_SHELF -> "LS"
                ParametricEqManager.TYPE_HIGH_SHELF -> "HS"
                else -> "PK"
            }
            sb.appendLine(
                "Filter: ON $typeStr Fc ${formatFreq(b.freq)} Hz Gain ${"%.2f".format(b.gain)} dB Q ${"%.3f".format(b.q)}"
            )
        }
        return sb.toString()
    }

    private fun formatFreq(f: Float): String =
        if (f % 1f == 0f) f.toInt().toString() else "%.1f".format(f)
}
