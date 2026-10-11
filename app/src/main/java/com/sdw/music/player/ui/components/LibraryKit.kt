package com.sdw.music.player.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.sdw.music.player.ui.components.SDWDivider
import com.sdw.music.player.ui.theme.sectionTitleStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdw.music.player.ui.theme.SDWShapes
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sdw.music.player.R
import com.sdw.music.player.SongRepository
import com.sdw.music.player.ui.components.glassSurface
import com.sdw.music.player.ui.components.springPress
import com.sdw.music.player.ui.theme.SDWHeader
import com.sdw.music.player.ui.theme.SDWShape
import com.sdw.music.player.ui.theme.SDWSpace
import com.sdw.music.player.ui.theme.itemSubtitleCompact
import com.sdw.music.player.ui.theme.itemSubtitleStyle
import com.sdw.music.player.ui.theme.itemTitleStyle

// ============================================================================
// 【SDW 库页面设计语言 — 共享构件】
//
// 以前这些零件在 AlbumListScreen / ArtistListScreen / AlbumSongScreen /
// ArtistSongScreen / PlaylistDetailScreen / SongListScreen 里各写一份，于是同一个
// 「封面」变成 48/44/52 三种尺寸和圆形+圆角方两种形状，「歌曲数」在六个页面有六种
// 写法。这里集中实现一次，各页面全部改为调用，后续只改这一处。
// ============================================================================

/** 毫秒 → m:ss。全站唯一实现（以前 SongListScreen 与 AlbumSongScreen 各存了一份）。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

/** 计数文案的种类，对应 strings.xml 里的 count_* 格式化串。 */
enum class CountKind { SONGS, ALBUMS, ARTISTS, PLAYLISTS, FOLDERS, LOCATIONS }

/**
 * 统一的「N 首 / N 张专辑 / N 位歌手…」副标题。
 *
 * 以前每页都写 `"$n songs"` 这样的硬编码英文，中文界面里夹着一句英文。改走资源后
 * 九种语言都能正确变位（中文用「首」，英文按单复数）。
 */
@Composable
fun CountText(count: Int, kind: CountKind, modifier: Modifier = Modifier) {
    val res = when (kind) {
        CountKind.SONGS -> R.string.count_songs
        CountKind.ALBUMS -> R.string.count_albums
        CountKind.ARTISTS -> R.string.count_artists
        CountKind.PLAYLISTS -> R.string.count_playlists
        CountKind.FOLDERS -> R.string.count_folders
        CountKind.LOCATIONS -> R.string.count_locations
    }
    Text(
        text = stringResource(res, count),
        style = itemSubtitleStyle(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * 统一的分组头：3dp 主色竖条 + 主色字母，起排 12dp。
 * 专辑页原来 12/14dp，歌手页原来 16/10dp，同款视觉对齐到一套数值。
 */
@Composable
fun SectionHeader(letter: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(
            start = SDWHeader.Start,
            top = SDWHeader.Top,
            bottom = SDWHeader.Bottom
        )
    ) {
        Box(
            modifier = Modifier
                .width(SDWHeader.BarWidth)
                .height(SDWHeader.BarHeight)
                .background(MaterialTheme.colorScheme.primary, SDWShapes.Badge)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = letter,
            color = MaterialTheme.colorScheme.primary,
            style = itemSubtitleStyle().copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * 统一的行内封面：默认 logo 打底 + 真封面覆盖。
 *
 * 打底不是防御性代码 —— 扫描器对每一行都写非空 albumArtUri（无封面时是
 * /audio/albumart/<id> 的失效条目），只判 isNotBlank() 恒真，加载失败就是纯白块。
 */
@Composable
fun RowCover(
    title: String,
    artist: String,
    uri: String,
    size: Dp = 48.dp,
    isPlaying: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val shape: Shape = RoundedCornerShape(SDWShape.forCover(size.value.toInt()))
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // 正在播放的呼吸光晕必须在封面 Box 内部：它是比封面更大的装饰层，得溢出绘制
        // 而不是参与布局。作为兄弟节点会把整行右推。
        if (isPlaying) NowPlayingHalo(accent)
        DefaultCoverImage(
            songTitle = title,
            songArtist = artist,
            modifier = Modifier.fillMaxSize(),
            shape = shape
        )
        if (SongRepository.hasRealCover(uri)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(uri)
                    .size(size.value.toInt() * 3) // 3 倍采样，3x 屏不糊
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                // 必须跟打底图用同一个 shape 裁一次：打底（DefaultCoverImage）自己会 clip，
                // 但真封面是 AsyncImage 直接铺上来的，不裁就是一张方图盖在圆角占位上。
                // 96dp 的详情页大封面最明显，行内小封面因为小也容易被忽略。
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 统一列表行：封面 + 主副标题 + 尾部。
 *
 * 正在播放的呈现在全站固定为三件套：整行主色水洗、上缘主色光晕、时长换成跳动波形。
 * 以前专辑/歌手页只把歌名染成主色、歌单页完全没有标识 —— 播到哪首完全看不出来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverTitle: String = "",
    coverArtist: String = "",
    coverUri: String = "",
    coverSize: Dp = 48.dp,
    isPlaying: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    durationMs: Long = 0,
    showDuration: Boolean = false,
    showNowPlayingBars: Boolean = false,
    showTrailing: Boolean = true,
    trailing: @Composable () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    /** 自定义前导区。歌手行传 ArtistAvatar（渐变字母头像），其它行留空走默认封面。 */
    leading: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val rowShape = RoundedCornerShape(SDWShape.Row)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SDWSpace.ListInline, vertical = 2.dp)
            .glassSurface(
                shape = rowShape,
                accent = accent,
                selected = isPlaying,
                elevation = 0.dp
            )
            .then(
                if (isPlaying) Modifier.border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.60f), accent.copy(alpha = 0.10f))
                    ),
                    shape = rowShape
                ) else Modifier
            )
            .springPress(interaction, pressedScale = 0.975f)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = SDWSpace.RowInline, vertical = SDWSpace.RowBlock),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
        } else {
            RowCover(
                title = coverTitle,
                artist = coverArtist,
                uri = coverUri,
                size = coverSize,
                isPlaying = isPlaying,
                accent = accent
            )
        }

        Spacer(Modifier.width(SDWSpace.ItemGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = itemTitleStyle(),
                color = if (isPlaying) accent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = itemSubtitleCompact(),
                    color = if (isPlaying) accent.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showNowPlayingBars || showDuration || showTrailing) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                when {
                    showNowPlayingBars -> NowPlayingBars(accent)
                    showDuration -> Text(
                        text = formatDuration(durationMs),
                        style = itemSubtitleStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Unit
                }
                if (showTrailing) trailing()
            }
        }
    }
}

/**
 * 统一的空态：图标 + 主文案 + 次文案。
 * 各页原来有的用 Folder 图标有的用 MusicNote，文案各不相同。
 */
@Composable
fun LibraryEmptyState(
    icon: ImageVector,
    message: String,
    hint: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    style = itemSubtitleStyle()
                )
            }
        }
    }
}

/** 「播放全部」入口条：主色播放图标 + 主色标题 + 首数，整行可点。 */
@Composable
fun PlayAllBar(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SDWSpace.ListInline, vertical = 2.dp)
            .glassSurface(shape = RoundedCornerShape(SDWShape.Row), fillBoost = 0f)
            .clip(RoundedCornerShape(SDWShape.Row))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = SDWSpace.RowInline, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.action_play_all),
            color = MaterialTheme.colorScheme.primary,
            style = itemTitleStyle()
        )
        Spacer(Modifier.width(6.dp))
        CountText(count, CountKind.SONGS)
    }
}

/** 正在播放的行标记：统一成主色小图标（歌手页原来用它、专辑页没有、歌单页没有）。 */
@Composable
fun NowPlayingMark(accent: Color = MaterialTheme.colorScheme.primary) {
    Icon(
        Icons.AutoMirrored.Filled.PlaylistPlay,
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size(20.dp)
    )
}

/**
 * 正在播放行的封面呼吸光晕。只有 isPlaying 的行会组合它，其余行零开销。
 *
 * requiredSize 而不是 size：父 Box 固定封面尺寸，size(64dp) 会被约束压回同样的值，
 * 光晕就完全被封面盖住。requiredSize 不受父约束，才能在封面外露出一圈。
 */
@Composable
fun NowPlayingHalo(accentColor: Color, shape: Shape = CircleShape) {
    val transition = rememberInfiniteTransition(label = "rowHalo")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rowHaloAlpha"
    )
    Box(
        modifier = Modifier
            .requiredSize(64.dp)
            .clip(shape)
            .background(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to accentColor.copy(alpha = alpha * 0.55f),
                        0.72f to accentColor.copy(alpha = alpha),
                        1.00f to accentColor.copy(alpha = 0f)
                    )
                )
            )
    )
}

/**
 * 正在播放行的跳动波形（三根竖条，520/660/800ms 三档周期错开，比同步缩放自然）。
 * 只在 isPlaying 的行组合，其余行不进合成。
 */
@Composable
fun NowPlayingBars(accentColor: Color) {
    val transition = rememberInfiniteTransition(label = "npBars")
    val peaks = listOf(9f, 15f, 11f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        peaks.forEachIndexed { i, peak ->
            val h by transition.animateFloat(
                initialValue = 5f,
                targetValue = peak,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520 + i * 140, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "npBar$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(SDWShapes.Badge)
                    .background(accentColor)
            )
        }
    }
}

// ============================================================================
// 详情页统一的 Hero 区块
// ============================================================================

/**
 * 统一的详情页头图：左封面 + 右标题/副信息/播放按钮的横排结构。
 *
 * 专辑页原来用 80dp 小封面横排、歌手页用 200dp 满宽大图、歌单页干脆没有头图 ——
 * 三个详情页三种骨架。统一为「横排 hero」：信息密度高，小屏也不至于把列表挤下去。
 */
@Composable
fun DetailHero(
    title: String,
    subtitle: @Composable () -> Unit,
    coverTitle: String = "",
    coverArtist: String = "",
    coverUri: String = "",
    coverSize: Dp = 96.dp,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SDWSpace.Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowCover(
            title = coverTitle,
            artist = coverArtist,
            uri = coverUri,
            size = coverSize
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = sectionTitleStyle(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            subtitle()
            Spacer(Modifier.height(10.dp))
            PlayAllPill(onPlayAll)
        }
    }
    Spacer(Modifier.height(6.dp))
    SDWDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
}

/** 「播放全部」胶囊按钮：描边 + 主色文字，比 TextButton 有分量，比 FAB 克制。 */
@Composable
fun PlayAllPill(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(SDWShapes.Pill)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                shape = SDWShapes.Pill
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.action_play_all),
                color = MaterialTheme.colorScheme.primary,
                style = itemSubtitleStyle().copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}
