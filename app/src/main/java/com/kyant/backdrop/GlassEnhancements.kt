package com.kyant.backdrop

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightElement
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.internal.ShapeProvider
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.InnerShadowElement

/**
 * 玻璃表面高光（边缘流光）——Kyant Highlight 的独立 Modifier 封装。
 *
 * 与完整版 `Modifier.drawBackdrop(...)` 里的 highlight 参数同一实现（HighlightElement），
 * 但不依赖 Backdrop（同位兄弟层）模型——可直接套在任何玻璃元素上，包括我们主屏
 * 自绘的胶囊模糊层（SongListScreen.MiniPlayer 的 capsuleLayer 方案）。
 *
 * 强度提示：HighlightNode 内部 `strokeWidth = ceil(width.toPx()) * 2`，再 clip 到形状内，
 * 所以**可见宽度约等于 width**。Kyant 库默认 0.5.dp 是发丝级、肉眼几乎不可见，
 * 这里默认放大到 2.dp；再叠 blurRadius 让亮边有柔和扩散而不是硬线。
 *
 * @param shape 玻璃形状（需与玻璃裁剪同形，圆角来自这里）
 * @param angle 高光角度（度数，0=从左，90=从上）。**刻意做成 lambda**：Kyant 的
 *   HighlightNode 是在 `ContentDrawScope.draw()` 里调用 `highlight()` 的，所以在这里
 *   读取 State 只会 invalidate draw，不会触发重组——传感器 50fps 更新也不会重组整条 MiniPlayer。
 * @param width 高光描边宽度（可见宽度≈此值，建议 1.5~3.dp）
 * @param blurRadius 高光模糊半径，让亮边柔和
 * @param alpha 整体透明度（会与 style.color 的 alpha 相乘）
 * @param falloff 高光衰减（越小越铺开、越亮；>1 更集中在边缘）
 */
fun Modifier.glassHighlight(
    shape: () -> Shape,
    angle: () -> Float = { 45f },
    width: Dp = 2.dp,
    blurRadius: Dp = 2.dp,
    alpha: Float = 1f,
    color: Color = Color.White.copy(alpha = 0.95f),
    falloff: Float = 0.8f
): Modifier = this.then(
    HighlightElement(
        shapeProvider = ShapeProvider(shape),
        highlight = {
            Highlight(
                width = width,
                blurRadius = blurRadius,
                alpha = alpha,
                style = HighlightStyle.Default(
                    color = color,
                    angle = angle(),
                    falloff = falloff
                )
            )
        }
    )
)

/**
 * 玻璃内阴影（厚度感）——Kyant InnerShadow 的独立 Modifier 封装。
 *
 * 实现原理：先用 color 填充整个形状，再把「形状按 offset 平移」的部分以 BlendMode.Clear
 * 擦掉，剩下的月牙即内阴影；最后整层再套 radius 半径的模糊。
 * 因此**保留带的宽度 ≈ offset.y**，而 radius 只负责把它摊柔。
 *
 * 强度提示：最终 alpha = shadow.alpha × color.alpha，二者相乘容易调到看不见，
 * 故这里默认 color 取**不透明**的 Color.Black，只用 alpha 单点控制强度。
 *
 * @param shape 玻璃形状（与玻璃裁剪同形）
 * @param radius 模糊半径（柔和度）
 * @param offset 擦除偏移，决定哪一侧、多宽（默认向下 radius = 顶部内阴影带）
 * @param alpha 阴影强度（深色玻璃 0.35~0.5 才明显）
 * @param color 阴影颜色（默认纯黑，不要再预乘 alpha）
 */
fun Modifier.glassInnerShadow(
    shape: () -> Shape,
    radius: Dp = 20.dp,
    offset: DpOffset = DpOffset(0.dp, radius),
    alpha: Float = 0.45f,
    color: Color = Color.Black
): Modifier = this.then(
    InnerShadowElement(
        shapeProvider = ShapeProvider(shape),
        shadow = {
            InnerShadow(
                radius = radius,
                offset = offset,
                color = color,
                alpha = alpha
            )
        }
    )
)
