package com.sdw.music.player.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color

/**
 * 【V8.205】封面取色的「降饱和收敛」—— 全站唯一入口。
 *
 * ── 为什么要这一层 ──
 * V8.176 / V8.177 为了让 VU 柱「一眼可辨是封面那支色」，把封面 Palette 的色相与
 * 饱和度**原样**搬到了 UI：柱子甚至去掉 alpha（透明会洗掉饱和度）。而取色算法挑的本来就是
 * 封面最艳的那几档（S 普遍 0.85~1.0），同一个颜色同时喂给 VU 柱、歌词 glow、歌名、
 * 按键、播放条 —— 深色背景上一整片高饱和，长时间看就是刺眼。
 *
 * ── 这里的口径 ──
 * 只做一件事：**色相原样保留**，饱和度和明度各做一次软膝（soft knee）收敛 —— 超过膝点的
 * 部分只按比例保留一小截。低饱和的淡色原样通过（本来就不刺眼），高饱和的被压回来。
 * 于是「这是封面那支红」的识别度不掉，艳度却落进眼睛舒服的区间。
 *
 * 别在调用处各自手写 clamp：那样下次加一处新的强调色就又漏一次。
 */
object CoverTone {

    /** 饱和度软膝起点：低于它原样通过（淡色不刺眼，压了反而发脏）。 */
    private const val SAT_KNEE = 0.50f
    /** 软膝斜率：超过膝点的部分只保留这个比例（0.2 = 砍掉八成艳度）。 */
    private const val SAT_SOFT = 0.20f
    /** 饱和度地板：取色常挑出近灰档，原样用柱子会灰死，抬一点点保住「这是有色的」。 */
    private const val SAT_FLOOR = 0.24f

    /** 明度下限：文字/图标要靠它保证可读性。 */
    private const val VAL_MIN = 0.45f
    /** 明度软膝起点：深色背景上超过它就开始晃眼。 */
    private const val VAL_KNEE = 0.82f
    /** 明度软膝斜率。 */
    private const val VAL_SOFT = 0.30f

    /** 把一组 ARGB 收敛成不刺眼的强调色；0（=「无取色」，交给主题兜底）原样返回。 */
    fun calm(argb: Int): Int {
        if (argb == 0) return 0
        val hsv = FloatArray(4)
        AndroidColor.colorToHSV(argb, hsv)
        var s = hsv[1]
        if (s > SAT_KNEE) s = SAT_KNEE + (s - SAT_KNEE) * SAT_SOFT
        s = s.coerceIn(SAT_FLOOR, 1f)
        var v = hsv[2]
        if (v > VAL_KNEE) v = VAL_KNEE + (v - VAL_KNEE) * VAL_SOFT
        v = v.coerceIn(VAL_MIN, 1f)
        // alpha 原样带回去（第 4 位），别让降饱和把按钮的半透明抹成实心
        hsv[1] = s
        hsv[2] = v
        hsv[3] = ((argb ushr 24) and 0xFF).toFloat()
        return AndroidColor.HSVToColor(hsv)
    }

    /** Compose 版本；全透明原样返回。 */
    fun calm(color: Color): Color {
        if (color.alpha == 0f) return color
        return Color(
            AndroidColor.argb(
                (color.alpha * 255f).toInt().coerceIn(0, 255),
                (color.red * 255f).toInt().coerceIn(0, 255),
                (color.green * 255f).toInt().coerceIn(0, 255),
                (color.blue * 255f).toInt().coerceIn(0, 255)
            ).let(::calm)
        )
    }

    /** 列表版本：调色板 5 档一起收敛，0 位（未取到）保持不变。 */
    fun calmAll(colors: List<Int>): List<Int> {
        if (colors.isEmpty()) return colors
        return colors.map { calm(it) }
    }
}
