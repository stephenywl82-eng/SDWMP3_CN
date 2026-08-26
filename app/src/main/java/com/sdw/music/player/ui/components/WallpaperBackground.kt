package com.sdw.music.player.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.sdw.music.player.WallpaperManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局壁纸背景层：铺在 NavHost 底下的最底层。
 *  - 壁纸图片铺满 + 可调模糊
 *  - 顶部压暗 scrim（取色感知开启时按亮度动态调整强度）
 * 播放器路由豁免：播放器自身有封面背景，会用自己的不透明层盖住这里。
 */
@Composable
fun WallpaperBackground() {
    val path = WallpaperManager.wallpaperPath.value ?: return
    val blurAmount = WallpaperManager.blurAmount.value
    val scrimAmount = WallpaperManager.scrimAmount.value
    val colorAware = WallpaperManager.colorAwareEnabled.value
    val brightness = WallpaperManager.wallpaperBrightness.value
    val context = LocalContext.current

    // Palette 亮度分析：取壁纸主色，算感知亮度（0..1），回写 WallpaperManager
    LaunchedEffect(path) {
        val b = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                val bmp = BitmapFactory.decodeFile(path, opts) ?: return@runCatching 0.5f
                val p = androidx.palette.graphics.Palette.from(bmp).generate()
                val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch
                ?: p.dominantSwatch ?: p.mutedSwatch ?: p.lightMutedSwatch
                val rgb = swatch?.rgb ?: return@runCatching 0.5f
                bmp.recycle()
                // 感知亮度（Rec.601 luma），范围 0..1
                val r = ((rgb shr 16) and 0xFF) / 255f
                val g = ((rgb shr 8) and 0xFF) / 255f
                val bl = (rgb and 0xFF) / 255f
                0.299f * r + 0.587f * g + 0.114f * bl
            }.getOrDefault(0.5f)
        }
        WallpaperManager.updateBrightness(b)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 壁纸本体（可调模糊）
        Image(
            painter = rememberAsyncImagePainter(File(path)),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(if (blurAmount > 0f) blurAmount.dp else 0.dp),
            contentScale = ContentScale.Crop
        )

        // 顶部压暗 scrim：默认按手动 scrimAmount；取色感知开启时，壁纸越亮压得越深
        val effectiveScrim = if (colorAware) {
            // 亮壁纸（brightness→1）scrim 取满，暗壁纸（brightness→0）减半
            (scrimAmount * (0.5f + 0.5f * brightness)).coerceIn(0f, 0.9f)
        } else {
            scrimAmount
        }
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to Color.Black.copy(alpha = effectiveScrim),
                    0.35f to Color.Black.copy(alpha = effectiveScrim * 0.55f),
                    0.70f to Color.Black.copy(alpha = effectiveScrim * 0.30f),
                    1.0f to Color.Black.copy(alpha = effectiveScrim * 0.85f)
                )
            )
        }
    }
}
