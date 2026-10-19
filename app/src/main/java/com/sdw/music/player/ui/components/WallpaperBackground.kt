package com.sdw.music.player.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.sdw.music.player.R
import com.sdw.music.player.WallpaperManager
import com.sdw.music.player.ui.theme.DarkBg
import com.sdw.music.player.ui.theme.sdwEffectiveLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局背景层：铺在 NavHost 底下的最底层。
 *
 * V8.113 真·液态玻璃：这一层永远绘制，作为所有 glassSurface 面板背后的底图。
 *  - 设置了壁纸：画模糊壁纸 + 顶部压暗 scrim（原逻辑，真模糊）。
 *  - 未设置壁纸：纯色底 + 居中 Motorola 蝙蝠翼 M 矢量水印（用户指定：背景用 Motorola logo）。
 *    注：res 里的 ic_motorola_m.png 是截图翻拍（棋盘格烤进像素、logo 右缘被裁），不可用；
 *    改用项目内已有的矢量 logo ic_notif_brand.xml（完整、任意缩放不糊、可按深浅色染色）。
 * 播放器/全屏歌词路由豁免（见 SDWNavigation，它们用各自的不透明背景盖住这里）。
 */
@Composable
fun WallpaperBackground() {
    val wallpaperPath = WallpaperManager.wallpaperPath.value
    val blurAmount = WallpaperManager.blurAmount.value
    val scrimAmount = WallpaperManager.scrimAmount.value
    val colorAware = WallpaperManager.colorAwareEnabled.value
    val brightness = WallpaperManager.wallpaperBrightness.value
    val light = sdwEffectiveLight()

    // 有壁纸：保留原真模糊逻辑
    if (wallpaperPath != null) {
        // 亮度分析：全图下采样逐像素平均感知亮度（0..1），回写 WallpaperManager。
        // 【2026-09-04】原 palette vibrant swatch 会漏掉白色主体（白车身非"鲜艳色"被忽略、
        // 误取暗色边缘），导致亮壁纸误判为暗、白字失效。全图平均更贴近真实观感。
        LaunchedEffect(wallpaperPath) {
            val b = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
                    val bmp = BitmapFactory.decodeFile(wallpaperPath, opts) ?: return@runCatching 0.5f
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
                painter = rememberAsyncImagePainter(File(wallpaperPath)),
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = effectiveScrim),
                        0.35f to Color.Black.copy(alpha = effectiveScrim * 0.55f),
                        0.70f to Color.Black.copy(alpha = effectiveScrim * 0.30f),
                        1.0f to Color.Black.copy(alpha = effectiveScrim * 0.85f)
                    )
                )
            }
        }
        return
    }

    // 无壁纸：纯色底 + 居中 Motorola 蝙蝠翼 M 水印（用户指定背景用 Motorola logo）。
    // 深色模式：白 logo 低透明度；浅色模式：深 logo 更低透明度（不与文字抢对比度）。
    // 强度/尺寸都是单行可调参数，用户觉得淡了/小了直接改这里。
    val base = if (light) Color(0xFFF5F5F7) else DarkBg
    val logoTint = if (light) Color(0xFF1A1A1C) else Color.White
    val logoAlpha = if (light) 0.10f else 0.16f

    Box(
        modifier = Modifier.fillMaxSize().background(base),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_notif_brand),
            contentDescription = null,
            modifier = Modifier.size(300.dp),
            colorFilter = ColorFilter.tint(logoTint),
            alpha = logoAlpha
        )
    }
}
