package com.sdw.music.player.ui.components
import com.sdw.music.player.core.audio.metadata.CoverDownloader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sdw.music.player.util.PinyinUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌手头像：默认 MD3 首字母色块；开启「艺人头像」后按设置的来源
 * （本地文件夹 / Wikimedia / Last.fm）联网或本地取图并缓存显示。
 */
@Composable
fun ArtistAvatar(
    artistName: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("sdw_music_prefs", Context.MODE_PRIVATE)
    val enabled = prefs.getBoolean("artist_avatar_enabled", true)

    var avatarFile by remember(artistName) { mutableStateOf<File?>(null) }
    var tried by remember(artistName) { mutableStateOf(false) }

    LaunchedEffect(artistName, enabled) {
        if (!enabled) {
            avatarFile = null
            tried = false
            return@LaunchedEffect
        }
        if (tried) return@LaunchedEffect
        tried = true
        withContext(Dispatchers.IO) {
            avatarFile = CoverDownloader.downloadArtistAvatar(context, artistName)
        }
    }

    if (enabled && avatarFile != null) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(avatarFile).crossfade(true).build(),
            contentDescription = artistName,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape)
        )
        return
    }

    // 首字母色块头像（默认 / 离线 / 未抓到时回落）
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
