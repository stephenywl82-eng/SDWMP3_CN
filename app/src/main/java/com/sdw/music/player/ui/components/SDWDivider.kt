package com.sdw.music.player.ui.components

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 【全站唯一分隔线】
 *
 * 以前同一条「卡片内的细线」有三种写法：设置页的 `SettingsCardDivider` 把 alpha 压到 0.35、
 * MSEB 页直接用默认 `Divider`（alpha 1、粗细 1dp，在玻璃面上刺眼），歌词搜索页则是 0.1。
 * 三处各调各的，滚起来像三段不同的界面。
 *
 * 统一口径：0.5dp 细线 + outlineVariant 的 0.35 alpha（在玻璃/毛玻璃背景上既看得见又不抢戏）。
 * 需要别的强度时显式传 alpha，不许在调用处再抄一份 outlineVariant.copy().
 */
@Composable
fun SDWDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 0.5.dp,
    /**
     * 颜色。默认 outlineVariant @ 0.35（在玻璃面上既看得见又不抢戏）。
     * 接口留成 Color 而不是只有 intensity，是因为历史调用点传的原始色各不相同
     * （onSurface@0.05 / outlineVariant@0.1），一刀切成 0.35 反而会把某些地方压没了；
     * 真要强调某条分隔线时传 color，其余场景别动这个参数。
     */
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = color
    )
}
