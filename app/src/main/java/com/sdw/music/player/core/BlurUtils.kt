package com.sdw.music.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.annotation.RequiresApi
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 【v5.22】模糊工具类
 * 支持 RenderScript (API 17+, Android 4.2+)
 * fallback: Stack Blur (纯 Java，所有版本)
 */
object BlurUtils {

    private const val MAX_BLUR_RADIUS = 25f

    /**
     * 异步模糊图片
     * @param context Context
     * @param uri 图片 URI
     * @param radius 模糊半径 (1-25)
     * @param scale 缩放比例（越小越快，如 0.1 表示缩小到 10% 再模糊）
     * @param callback 回调
     */
    fun blurAsync(
        context: Context,
        uri: Uri,
        radius: Float = 18f,
        scale: Float = 0.15f,
        callback: (Bitmap?) -> Unit
    ) {
        Glide.with(context)
            .asBitmap()
            .load(uri)
            .into(object : SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val scaled = scaleBitmap(resource, scale)
                    val blurred = blur(context, scaled, radius)
                    callback(blurred)
                }

                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    callback(null)
                }
            })
    }

    /**
     * 异步模糊图片（协程版本）
     */
    suspend fun blurSuspend(
        context: Context,
        uri: Uri,
        radius: Float = 18f,
        scale: Float = 0.15f
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        blurAsync(context, uri, radius, scale) { bmp ->
            cont.resume(bmp)
        }
    }

    /**
     * 模糊 Bitmap（自动选择 RenderScript 或 Stack Blur）
     */
    fun blur(context: Context, bitmap: Bitmap, radius: Float): Bitmap {
        val safeRadius = radius.coerceIn(1f, MAX_BLUR_RADIUS)
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                blurRenderScript(context, bitmap, safeRadius)
            } else {
                blurStack(bitmap, (safeRadius * 0.5f).toInt().coerceAtLeast(1))
            }
        } catch (e: Exception) {
            blurStack(bitmap, (safeRadius * 0.5f).toInt().coerceAtLeast(1))
        }
    }

    /**
     * RenderScript 高质量模糊（Android 4.3+）
     */
    @RequiresApi(android.os.Build.VERSION_CODES.JELLY_BEAN_MR1)
    private fun blurRenderScript(context: Context, source: Bitmap, radius: Float): Bitmap {
        val rs = RenderScript.create(context)
        val input = Allocation.createFromBitmap(rs, source)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius.coerceAtMost(25f))
        script.setInput(input)
        script.forEach(output)
        output.copyTo(source)
        rs.destroy()
        return source
    }

    /**
     * Stack Blur 算法（纯 Java，所有 Android 版本）
     * 来自 Mario Klingemann / Quasimondo
     */
    private fun blurStack(bitmap: Bitmap, radius: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in 0 until 256 * divsum) {
            dv[i] = (i / divsum)
        }

        yi = 0
        yw = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (y in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            rbs = r1
            for (i in -radius until radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[(stackpointer) % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            yp = -radius * w
            for (i in -radius until radius) {
                yi = maxOf(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                yp += w
            }
            yi = x
            stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (y == 0) {
                    vmin[y] = minOf(y + radius + 1, hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi += w
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * 缩放 Bitmap（用于加速模糊）
     */
    private fun scaleBitmap(src: Bitmap, scale: Float): Bitmap {
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * 从 drawable resource 快速模糊
     */
    fun blurFromResource(context: Context, resId: Int, radius: Float = 18f, scale: Float = 0.15f): Bitmap? {
        val drawable = context.resources.getDrawable(resId, null) ?: return null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        val scaled = scaleBitmap(bitmap, scale)
        return blur(context, scaled, radius)
    }

    /**
     * 从 Uri 同步模糊（需在 IO 线程调用）
     */
    fun blurSync(context: Context, uri: Uri, radius: Float = 18f, scale: Float = 0.15f): Bitmap? {
        return try {
            val input = context.contentResolver.openInputStream(uri)
            val raw = android.graphics.BitmapFactory.decodeStream(input)
            input?.close()
            raw?.let {
                val scaled = scaleBitmap(it, scale)
                blur(context, scaled, radius)
            }
        } catch (e: Exception) {
            null
        }
    }
}
