package com.sdw.music.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.sdw.music.player.MusicService
import com.sdw.music.player.SongRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 【V8.377】DJ 竖屏界面 —— 照 djay Automix 竖屏截图做。
 *
 * 之前只做了横屏，竖屏时会把横屏布局硬塞进窄屏（挤成一团）。
 * 这个文件补上竖屏形态：
 *   顶部 automix 小圆点 -> 大封面 -> 艺人/歌名/剩余时间
 *   -> 彩虹频谱条 -> 整曲波形（带切片标记、可点跳）
 *   -> 底部 上一曲 / 播放 / 下一曲 + BPM
 *
 * 与横屏共用 [SpectrumBar] 和 [FullSongWaveform]（数据完全同源，只换排列）。
 * 本文件在 DJ 关闭时一行都不会执行 —— 与 V8.363 普通模式警戒线一致。
 */
@Composable
fun DjPortraitSurface(
    songTitle: String,
    songArtist: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onMixNow: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val xf by remember { androidx.compose.runtime.derivedStateOf {
        MusicService.instance?.crossfadeUiInfo ?: MusicService.CrossfadeUiInfo()
    } }

    val curSong by MusicService.songChangedFlow.collectAsState(initial = null)
    val coverUri = curSong?.albumArtUri?.takeIf { SongRepository.hasRealCover(it) }

    // 播放进度与剩余时间（500ms 轮询，与横屏同频）
    var playProgress by remember { mutableFloatStateOf(0f) }
    var remainMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val p = MusicService.instance?.oboeDirectPlayer
            val cur = p?.getCurrentPositionMs() ?: 0L
            val dur = p?.getDurationMs() ?: 0L
            remainMs = if (dur > 0) (dur - cur).coerceAtLeast(0L) else 0L
            if (dur > 0) playProgress = (cur.toFloat() / dur).coerceIn(0f, 1f)
            // 【V8.378】33ms ≈ 30fps（之前 500ms 播放头跳格，观感卡顿）
            delay(33)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---- automix 指示点：djay 顶部那个小圆。过渡中亮起 ----
        val active = xf.state == MusicService.XfadeUiState.ACTIVE
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E1E22)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFFE8A030) else Color(0xFF4A4A50))
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 大封面：占满可用宽度（djay 竖屏的绝对主角） ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1E)),
            contentAlignment = Alignment.Center
        ) {
            if (coverUri != null) {
                androidx.compose.foundation.Image(
                    painter = rememberAsyncImagePainter(model = coverUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF3A3A40),
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 艺人 / 歌名 + 剩余时间 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songArtist.ifBlank { "未知艺人" },
                    color = Color(0xFF9AA0A6),
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = songTitle.ifBlank { "未在播放" },
                    color = Color(0xFFE8EAED),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (remainMs > 0) {
                    val s = remainMs / 1000
                    "-%02d:%02d".format(s / 60, s % 60)
                } else "--:--",
                color = Color(0xFFB8BCC0),
                fontSize = 17.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---- 彩虹频谱条（与横屏同源同款） ----
        SpectrumBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
        )

        Spacer(Modifier.height(10.dp))

        // ---- 整曲波形 + 切片标记 + 点按跳转（吃掉剩余高度） ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            FullSongWaveform(
                modifier = Modifier.fillMaxSize(),
                progress = playProgress,
                onSeekToFraction = { frac ->
                    val p = MusicService.instance?.oboeDirectPlayer
                    val dur = p?.getDurationMs() ?: 0L
                    if (dur > 0) p?.seekTo((dur * frac).toLong())
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- 底部：立即混合 / 播放 / 下一曲 + BPM ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PortraitRoundKey(
                icon = Icons.Default.PlayArrow,
                label = "混合",
                onClick = onMixNow,
                enabled = xf.nextTitle.isNotBlank(),
                small = true
            )
            Spacer(Modifier.weight(1f))
            PortraitRoundKey(
                icon = Icons.Default.PlayArrow,
                label = if (isPlaying) "暂停" else "播放",
                onClick = onPlayPause,
                enabled = true,
                big = true
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PortraitRoundKey(
                    icon = Icons.Default.SkipNext,
                    label = "下一曲",
                    onClick = onNext,
                    enabled = true,
                    small = true
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("BPM", color = Color(0xFF6A6A72), fontSize = 10.sp)
                    Text(
                        text = if (xf.aBpm > 0f) "%.1f".format(xf.aBpm) else "--",
                        color = Color(0xFFE8EAED),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** 竖屏圆形按键：djay 那种纯图标圆钮，big = 主播放键（更大）。 */
@Composable
private fun PortraitRoundKey(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    big: Boolean = false,
    small: Boolean = false
) {
    val sizeDp = when {
        big -> 64.dp
        small -> 46.dp
        else -> 54.dp
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(sizeDp)
                .clip(CircleShape)
                .background(if (big) Color(0xFF20301F) else Color(0xFF232327))
                .then(if (big) Modifier.border(2.dp, Color(0xFF5FA86A), CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) Color(0xFFE8EAED) else Color(0xFF4A4A50),
                modifier = Modifier.size(if (big) 30.dp else 22.dp)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color(0xFF8A8A92), fontSize = 10.sp)
    }
}
