package com.kyant.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import com.kyant.backdrop.internal.RoundedRectRefractionShaderString
import com.kyant.backdrop.internal.RuntimeShaderEffect

/**
 * 构造一个「圆角矩形折射」RenderEffect（Kyant Liquid Glass 的 lens 效果），用于在已有
 * 正确对齐的模糊图层上叠加液态折射。仅 API 33+（RuntimeShader）有效，低版本返回 null。
 *
 * 与库的 [com.kyant.backdrop.effects.lens] 不同：这里不依赖库的 LayerBackdrop 录制模型，
 * 而是直接对我们自己管理、已用 miniShift 平移对齐好的胶囊尺寸模糊图层生效，因此可以套在
 * 浮于滚动列表之上的迷你播放条等玻璃面上（库的 drawBackdrop 模型不适合滚动列表背景）。
 *
 * @param size              图层的像素尺寸（应与被折射图层尺寸一致）
 * @param cornerRadiusPx    圆角半径（px），应与玻璃面圆角一致（迷你条为 26.dp）
 * @param refractionHeightPx 折射带高度（px）
 * @param refractionAmountPx 折射强度（px）
 */
fun liquidGlassLens(
    size: Size,
    cornerRadiusPx: Float,
    refractionHeightPx: Float,
    refractionAmountPx: Float
): RenderEffect? {
    if (!isRuntimeShaderSupported()) return null
    val shader = RuntimeShader(RoundedRectRefractionShaderString)
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("offset", 0f, 0f)
    shader.setFloatUniform(
        "cornerRadii",
        floatArrayOf(cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx)
    )
    shader.setFloatUniform("refractionHeight", refractionHeightPx)
    shader.setFloatUniform("refractionAmount", -refractionAmountPx)
    shader.setFloatUniform("depthEffect", 0f)
    return RuntimeShaderEffect(shader, "content")
}
