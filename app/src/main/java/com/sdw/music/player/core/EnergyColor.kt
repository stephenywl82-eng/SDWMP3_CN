package com.sdw.music.player.core
import com.sdw.music.player.core.audio.dsp.LoudnessCache

import com.sdw.music.player.BpmKeyCache
import com.sdw.music.player.Song
/**
 * 【V8.244】Rekordbox 式「能量 / 颜色」标签。
 *
 * - energy：1..5，由 Integrated LUFS（响度）推算，越响能量越高；无响度数据时回退 BPM，
 *   再回退中性 3。作为 Automix / 自动流程排序的「能量维度」。
 * - color ：0..7，按 Camelot 调性稳定着色（同调聚类一目了然），无调性时按 id 散列兜底。
 *
 * 全部基于已有的 BpmKeyCache（Camelot 调性）与 LoudnessCache（LUFS），无需重新扫描。
 */
object EnergyColor {

    /** 能量 1..5：优先 Integrated LUFS，回退 BPM，再回退 3。 */
    fun energyOf(song: Song): Int {
        val path = song.filePath.ifEmpty { song.path }
        val lufs = if (path.isNotBlank()) LoudnessCache.get(path)?.lufs else null
        if (lufs != null && lufs > -60f) {
            val e = 1 + ((lufs + 20f) / 4f).toInt()
            return e.coerceIn(1, 5)
        }
        if (song.bpm > 0) {
            val e = 1 + ((song.bpm - 80) / 12).toInt()
            return e.coerceIn(1, 5)
        }
        return 3
    }

    /** 颜色 0..7：按 Camelot 调性稳定着色；无调性按 id 散列兜底。 */
    fun colorOf(song: Song): Int {
        val path = song.filePath.ifEmpty { song.path }
        val key = if (path.isNotBlank()) {
            BpmKeyCache.get(path)?.second?.takeIf { it.isNotBlank() } ?: song.key
        } else song.key
        val m = Regex("(\\d{1,2})\\s*([AB])").find(key.trim().uppercase())
        val num = m?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (num != null && num in 1..12) (num - 1) % 8 else (song.id.toInt() and 7)
    }

    /** 8 色 Rekordbox 风格调色板（深色背景上清晰，含 alpha 0xFF）。 */
    val PALETTE: List<Int> = listOf(
        0xFFE53935.toInt(), // red
        0xFFD81B60.toInt(), // pink
        0xFF8E24AA.toInt(), // purple
        0xFF3949AB.toInt(), // indigo
        0xFF1E88E5.toInt(), // blue
        0xFF00897B.toInt(), // teal
        0xFF43A047.toInt(), // green
        0xFFFB8C00.toInt()  // orange
    )

    /** 取颜色 int（含 alpha），越界安全。 */
    fun colorInt(song: Song): Int = PALETTE[colorOf(song).coerceIn(0, PALETTE.size - 1)]
}
