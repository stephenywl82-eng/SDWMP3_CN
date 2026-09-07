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

    // 亮度分析：全图下采样逐像素平均感知亮度（0..1），回写 WallpaperManager。
    // 【2026-09-04】原 palette vibrant swatch 会漏掉白色主体（白车身非"鲜艳色"被忽略、
    // 误取暗色边缘），导致亮壁纸误判为暗、白字失效。全图平均更贴近真实观感。
    LaunchedEffect(path) {
        val b = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
                val bmp = BitmapFactory.decodeFile(path, opts) ?: return@runCatching 0.5f
                var sum = 0.0
                var count = 0
                for (x in 0 until bmp.width step 2) {
                    for (y in 0 until bmp.height step 2) {
                        val rgb = bmp.getPixel(x, y)
                        val r = ((rgb shr 16) and 0xFF) / 255f
                        val g = ((rgb shr 8) and 0xFF) / 255f
                        val bl = (rgb and 0xFF) / 255f
                        sum += 0.299 * r + 0.587 * g + 0.114 * bl
                        count++
                    }
                }
                bmp.recycle()
                if (count == 0) 0.5f else (sum / count).toFloat()
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
