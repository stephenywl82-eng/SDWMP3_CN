package com.sdw.music.player.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
 * 统一口径（历史）：0.5dp 细线 + outlineVariant 的 0.35 alpha。
 *
 * [M3 Expressive · Phase 2] 线条已被「Surface 色阶」吞噬。
 * Expressive 的分区逻辑是*靠色块层级 + 留白*，绝不在卡片内部画生硬的横线——
 * 线会把整块 Surface 切成条状，正是"看起来不像 Expressive"的头号元凶。
 * 本组件因此改为纯留白：保留调用点（74 处）不动，视觉上线条全部消失，
 * 分区交给 background → surfaceContainerLow/High → primaryContainer 的色阶落差。
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
    // 不画任何线：只用留白撑开相邻两项，让背景色块连成一体。
    Spacer(modifier = modifier.height(12.dp))
}
