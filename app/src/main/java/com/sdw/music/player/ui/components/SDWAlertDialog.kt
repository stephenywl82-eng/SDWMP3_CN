package com.sdw.music.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sdw.music.player.ui.theme.SDWShapes

/**
 * 【全站唯一对话框】
 *
 * 以前每个页面自己调 `AlertDialog`：容器是实色 `surface`（或干脆不传，吃到默认的实色
 * surfaceContainerHigh），圆角是 material3 默认的 28dp 但也有的页面传了自己的值。结果是
 * 全站唯一的「弹出层」跟卡片、菜单、玻璃面板三套材质并存：列表是玻璃，弹窗是一块死白板。
 *
 * [P2 · M3 Expressive] 统一到这一处：
 *   · 容器走 glassSurface —— 半透明渐变 + 顶边高光，和页面里其它玻璃面同一种材质；
 *   · 圆角钉死 SDWShapes.Panel(26dp)：弹窗是「浮在最上面的一层」，比列表卡 Row(20) 再圆一档，
 *     跟迷你播放条、底部抽屉同一档；
 *   · fillBoost 抬高一点：对话框下面是被 dim 压暗的内容，玻璃太透会读不清正文。
 *
 * 参数命名与 material3 的 AlertDialog 保持一致（onDismissRequest / confirmButton /
 * dismissButton / icon / title / text / properties），所以老调用点通常只需要改函数名。
 */
@Composable
fun SDWAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(
            // 调用点里没写 color 的 Text 会拿到 onSurface，而不是 Compose 默认的黑色，
            // 免得"玻璃上的黑字"在深色分支下糊掉。
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = modifier
                    // 与 material3 AlertDialog 同样的宽度契约：太窄读不下正文，太宽不像弹窗。
                    .widthIn(min = 280.dp, max = 560.dp)
                    .glassSurface(
                        shape = SDWShapes.Panel,
                        elevation = 8.dp,
                        fillBoost = 0.18f
                    )
                    .padding(24.dp)
            ) {
                if (icon != null) {
                    Box(modifier = Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) {
                        icon()
                    }
                }
                if (title != null) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        Box(modifier = Modifier.padding(bottom = if (text != null) 16.dp else 0.dp)) {
                            title()
                        }
                    }
                }
                if (text != null) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        Box(modifier = Modifier.padding(bottom = 24.dp)) {
                            text()
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(Modifier.width(8.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}
