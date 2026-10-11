package com.sdw.music.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 【SDW 设计语言 — 全站唯一契约】
 *
 * 以前每个界面各自写死圆角、间距、字重和副标题，结果同一个「歌曲数」在六个页面里有六种
 * 写法，封面一会儿圆形一会儿圆角方、标题一会儿 SemiBold 一会儿 Normal。这里把它们固化成
 * 常量，任何新页面只允许引用本文件，不允许再出现字面量。
 *
 * ── 圆角阶梯（只准用这四个，不准再自己 new）──
 *   Row(16)   列表行 / 卡片
 *   Media(12) 专辑级封面
 *   Thumb(8)  行内缩略封面
 *   Badge(4)  小角标（HR / BPM）
 *
 * 历史遗留的一堆 2/3/4/6/8/10/12dp 全部按「就近归档」收敛到上面四档：
 *   2~4 → Badge，6~10 → Thumb，12 → Media，16 → Row。
 * 这是纯编码口径统一，实际像素差异在 1~2dp，视觉上不会跳，但全站不再有第五种圆角。
 *
 * ── 间距阶梯 ──
 *   Gutter(16)   页级左右安全边距（贴顶栏/底栏的内容）
 *   ListInline(10)   行容器左右外边距（行与屏幕边缘的距离）
 *   RowInline(12)    行内容左右内边距（封面与文字的距离）
 *   RowBlock(8)      行内容上下内边距
 *   Card(14)     卡片内容内边距（诊断/均衡/ quality 这类设置型卡片）
 *   CardTop(10)  卡片首行上内边距（与 Card 配合，压出 24dp 的首行呼吸）
 *
 * ── 封面形状统一为圆角方 ──
 * 主曲库原来是 CircleShape，专辑/歌手/歌单页是 RoundedCornerShape —— 同一个 App 里两种
 * 封面语言。选圆角方的理由：一是封面本身是方图，圆形会裁掉四角；二是这个播放器定位是本地
 * 高保真，几何感比社交 App 的圆润更贴气质；三是 ArtistAvatar 已经是圆形，混用最糟。
 *
 * ── 文本层级（两个层级，不准再加第三种）──
 *   itemTitle()      列表主标题：bodyMedium + SemiBold
 *   itemSubtitle()   列表副标题：bodySmall
 *   pageTitle()      页面标题（顶栏）：titleLarge
 *                    —— 以前顶栏有的页面不写 style（吃到 TopAppBar 默认的 titleLarge = 24sp），
 *                       有的页面手写 titleMedium = 16sp，同一个 App 里标题字号差了一档。
 */
object SDWShape {
    val Row = 16.dp
    val Media = 12.dp
    val Thumb = 8.dp
    val Badge = 4.dp

    /** 全圆角胶囊：播放键、Pill 按钮、标签。 */
    val Pill = 50.dp
    /** 悬浮层专用：迷你播放器这类浮在内容之上的面板，比卡片再圆一档才有「浮起来」的感觉。 */
    val Panel = 26.dp

    /** 封面圆角（按传入尺寸选档，别在调用处再手写）。 */
    fun forCover(sizeDp: Int) = if (sizeDp >= 96) Media else Thumb
}

/**
 * 圆角阶梯的【现成 Shape】版本。
 *
 * 存在理由：`SDWShapes.Row` 这种写法即使数值对，也没把「16 是 Row 档」这件事
 * 表达出来，下一个人看到只会再抄一个 16。调用处一律写 `SDWShapes.Row`。
 */
object SDWShapes {
    val Row: Shape get() = RoundedCornerShape(SDWShape.Row)
    val Media: Shape get() = RoundedCornerShape(SDWShape.Media)
    val Thumb: Shape get() = RoundedCornerShape(SDWShape.Thumb)
    val Badge: Shape get() = RoundedCornerShape(SDWShape.Badge)
    val Pill: Shape get() = RoundedCornerShape(SDWShape.Pill)
    val Panel: Shape get() = RoundedCornerShape(SDWShape.Panel)
}

object SDWSpace {
    val Gutter = 16.dp
    val ListInline = 10.dp
    val RowInline = 12.dp
    val RowBlock = 8.dp
    val ItemGap = 12.dp
    val GridInline = 12.dp
    val GridGapH = 10.dp
    val GridGapV = 8.dp
    val Card = 14.dp
    val CardStart = 16.dp
    val CardTop = 10.dp
}

/** 列表页统一分组头：3dp accent 竖条 + accent 字母，起排 12dp。 */
object SDWHeader {
    val BarWidth = 3.dp
    val BarHeight = 14.dp
    val Start = 12.dp
    val Top = 14.dp
    val Bottom = 4.dp
}

/**
 * 播放页走带区「五个键」的统一契约。
 *
 * 以前这五个键的尺寸是四个布局各自传参（竖屏 64 / 横屏 52 / 折叠左栏 48），排布一个
 * Arrangement.Center 一个 SpaceEvenly，沉浸模式和非沉浸模式长得完全不一样。现在只有这一份：
 * 不管哪个布局、开没开沉浸模式，五键的直径、留白和铺法全部一致，唯一例外是 Razr 外屏
 * （物理屏只有 488x414dp）由调用处传入更小的 playButtonSize。
 *
 * ★ 铺满（SpaceEvenly）不是随手选的：shuffle(48) + prev(56) 与 next(56) + repeat(48) 左右
 * 对称，中间夹着 play，所以 SpaceEvenly 出来的左右两侧留白正好相等，整排仍然严格居中。
 */
object SDWTransport {
    /** 播放/暂停键直径。 */
    val Play = 64.dp
    /** 上一首/下一首直径。 */
    val Step = 56.dp
    /** 随机/循环这两侧键直径。 */
    val Side = 48.dp
    /** 整排左右最小留白。 */
    val Gutter = 8.dp
}

/** 列表主标题。取的是当前 MaterialTheme 的 bodyMedium，所以必须 @Composable。 */
@Composable
fun itemTitleStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)

/** 列表副标题。 */
@Composable
fun itemSubtitleStyle(): TextStyle =
    MaterialTheme.typography.bodySmall

/** 副标题在行内的字号：比标准 bodySmall 再小一档，压出层次。 */
@Composable
fun itemSubtitleCompact(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)

/**
 * 内容区分区标题（「28 种品牌预设」「歌词来源」这类小节头）。
 *
 * 以前各处直接 `style = MaterialTheme.typography.titleMedium`，字号随 Material3 版本浮动，
 * 而且和顶栏的 titleLarge 之间缺一档说明。现在两档边界写死：顶栏 titleLarge、分区 titleMedium。
 */
@Composable
fun sectionTitleStyle(): TextStyle =
    MaterialTheme.typography.titleMedium

/**
 * 顶栏页面标题。
 *
 * TopAppBar 会用 themeOverlays 把普通 Text 渲染成 titleLarge，所以那些「没写 style」的页面
 * 其实一直是 24sp；而手写 titleMedium 的页面是 16sp。这不是设计取舍，是漏写。全站统一到
 * titleLarge，顺带把字重钉死，避免以后又有人随手改 SemiBold。
 */
@Composable
fun pageTitleStyle(): TextStyle =
    MaterialTheme.typography.titleLarge
