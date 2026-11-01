package com.sdw.music.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * SDW 中性色板（仅 surface / 文字这类"与主题色无关"的层）。
 *
 * 强调色（primary / secondary / tertiary）**不在这里**——它们由 [sdwAutoScheme] 从
 * 「用户当前真正选中的系统主题色」实时派生，见 Theme.kt。早期版本在这里写死过
 * 蓝/金品牌双色（SDWDarkColorScheme / SDWLightColorScheme），现已整体移除：
 * 那两个方案已被全局动态取色取代，留着只会让人误以为配色是固定值。
 */

// ── 深色中性表面（SDW 黑阶）──
val DarkBg = Color(0xFF000000)      // 纯黑背景
val DarkCard = Color(0xFF0D0D0D)    // 卡片背景
val DarkSurface = Color(0xFF181818) // 表面

// ── 文字层级 ──
val TextPrimary = Color(0xFFF5F5F7) // 主文字
val TextSecondary = Color(0xFF8E8E93) // 副文字

/**
 * 状态绿。M3 色板里没有「成功」角色（只有 error），所以这一支保留为独立语义 token，
 * 不并入 colorScheme —— 它表达的是"在线 / 活跃 / 未削波"，不是主题强调色。
 */
val AccentGreen = Color(0xFF66BB6A)
