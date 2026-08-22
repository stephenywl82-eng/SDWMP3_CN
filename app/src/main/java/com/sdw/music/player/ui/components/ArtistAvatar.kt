package com.sdw.music.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdw.music.player.util.PinyinUtils

/**
 * 歌手首字母头像 —— 纯 Material 3 色彩容器风格：
 * 背景从 MD3 的 primary/secondary/tertiaryContainer 三个柔和粉彩容器色中
 * 按歌手名 hash 确定性轮换，文字用对应 onXxxContainer 色。
 * 无渐变、无玻璃高光、无投影、无描边，与全局 MD3 设计语言一致。
 */
@Composable
fun ArtistAvatar(
    artistName: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    val scheme = MaterialTheme.colorScheme

    // MD3 容器色板：柔和粉彩，确定性轮换
    val containers = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )

    val (bg, fg) = remember(artistName, scheme) {
        val idx = kotlin.math.abs(artistName.hashCode()) % containers.size
        containers[idx]
    }

    val initial = remember(artistName) {
        PinyinUtils.getInitial(artistName).uppercaseChar().toString()
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}
