package com.sdw.music.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.sdw.music.player.R

/** 精选调色板 - 18色 */
private val COVER_PALETTE = listOf(
    Color(0xFFE53935), // Red
    Color(0xFFD81B60), // Pink
    Color(0xFF8E24AA), // Purple
    Color(0xFF5E35B1), // Deep Purple
    Color(0xFF3949AB), // Indigo
    Color(0xFF1E88E5), // Blue
    Color(0xFF039BE5), // Light Blue
    Color(0xFF00ACC1), // Cyan
    Color(0xFF00897B), // Teal
    Color(0xFF43A047), // Green
    Color(0xFF7CB342), // Light Green
    Color(0xFFC0CA33), // Lime
    Color(0xFFFDD835), // Yellow
    Color(0xFFFFB300), // Amber
    Color(0xFFFB8C00), // Orange
    Color(0xFFF4511E), // Deep Orange
    Color(0xFF8D6E63), // Brown
    Color(0xFF78909C), // Blue Grey
)

/** 根据歌曲标识生成确定性颜色 */
@Composable
fun rememberCoverColor(songTitle: String, songArtist: String): Color {
    return remember(songTitle, songArtist) {
        val key = "$songTitle@$songArtist"
        val hash = key.hashCode()
        COVER_PALETTE[kotlin.math.abs(hash) % COVER_PALETTE.size]
    }
}

/**
 * 默认Cover组件：Motorola logo 以黑色线条渲染，
 * 颜色半透明叠层做氛围区分（同一首歌颜色始终一致）
 */
@Composable
fun DefaultCoverImage(
    songTitle: String,
    songArtist: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    contentScale: ContentScale = ContentScale.Crop,
    overlayAlpha: Float = 0.25f
) {
    val tintColor = MaterialTheme.colorScheme.primary

    Box(modifier = modifier) {
        // Dark background so lines are legible against light overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        // Logo in black lines
        Image(
            painter = painterResource(R.drawable.default_cover),
            contentDescription = stringResource(R.string.label_default_cover),
            modifier = Modifier.fillMaxSize().clip(shape),
            contentScale = contentScale,
            colorFilter = ColorFilter.tint(Color.Black)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(tintColor.copy(alpha = overlayAlpha))
        )
    }
}

