package com.sdw.music.player.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sdw.music.player.R
import com.sdw.music.player.ui.theme.pageTitleStyle

/**
 * 【全站唯一顶栏】
 *
 * 以前每个页面各写各的 TopAppBar：有的 title 不写 style（吃到 TopAppBar 默认 titleLarge=24sp），
 * 有的手写 titleMedium=16sp，有的连返回键 tint / 图标都不统一。用户在 A 页看到 24sp 标题、
 * 进 B 页变成 16sp，同一个 App 里两套标题层级。
 *
 * 抽成这一个组件后：标题字号只有一处定义，并用 ProvideTextStyle 兜底 —— 子 composable 就算
 * 忘了写 style 也会拿到 pageTitleStyle()，所以那几处漏写的不会再漏。
 *
 * 返回键默认实现也钉死：AutoMirrored 箭头 + onBackground tint。MSEB 这种从底部 sheet 上来的
 * 页面要下拉箭头，传 navigationIcon 显式覆盖即可，不用在各页复刻一份 IconButton。
 *
 * 参数顺序刻意把必填的 title 放第一位，其余全部带默认值，这样老调用点按任意命名参数顺序
 * 传都能编译（有的页面只给 navigationIcon 不给 onNavigateBack）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SDWTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    // 必须是 RowScope 版本：TopAppBar 的 actions 在 RowScope 里展开，
    // 写成普通 () -> Unit 会报「inferred type is () -> Unit but RowScope.() -> Unit was expected」。
    actions: @Composable RowScope.() -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background
    ),
    navigationIcon: @Composable () -> Unit = {
        val cb = onNavigateBack
        if (cb != null) {
            IconButton(onClick = cb) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
) {
    TopAppBar(
        title = {
            // 用 ProvideTextStyle 而不是给每个 Text 传 style：顶栏里的标题即使忘了写 style，
            // 也会继承到 pageTitleStyle()。以前正是「有的页面漏写 style」才出现两种标题字号。
            ProvideTextStyle(pageTitleStyle()) { title() }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        colors = colors
    )
}

/** 字符串标题的便利重载：把最常见的「一个标题 + 一个返回」缩短成两行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SDWTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    SDWTopBar(
        title = { Text(title) },
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        actions = actions
    )
}
