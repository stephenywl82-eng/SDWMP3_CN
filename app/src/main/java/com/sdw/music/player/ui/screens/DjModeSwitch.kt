package com.sdw.music.player.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * 【V8.357】播放界面的 DJ 模式开关。
 *
 * 设计意图（用户提出，已采纳）：**普通模式与 DJ 模式是两个不同的界面，不共用控件。**
 *
 * 本项目 8.300~8.356 之间二十来个版本、十几个 bug，几乎全部出自同一个根源：
 * DJ 交叉淡化与普通硬切**共用同一个播放界面、同一个"下一首"入口**，于是两套逻辑
 * 互相踩状态——
 *   · 硬切必须清xfadePreloading / crossfadeBusy / incoming 槽 / beat token，
 *     而 DJ 过渡必须设这些标志；
 *   · 两边轮流跑就出现「切了不动」「不动又自己切」「落点时长乱跳」。
 * 逐个打补丁不能根治，**把界面按模式分开才是结构性修法**。
 *
 * 因此：
 *   · `djEnabled == false` → 界面完全不出现 ⚡ DJ 键与 ⏭ 跳节拍键，
 *     切歌一律走最老的硬切路径（playNext → playSong），行为与老版本一致、最稳。
 *   · `djEnabled == true`  → 显示 DJ 键与跳节拍键，自动交叉淡化生效。
 *
 * 设置页改动会即时反映到播放界面（通过 OnSharedPreferenceChangeListener 监听），
 * 不需要重启应用。
 */
@Composable
fun rememberDjEnabled(context: Context): State<Boolean> {
    val state = remember { mutableStateOf(readDjEnabled(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CROSSFADE_DJ_ENABLED) {
                state.value = readDjEnabled(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** 同步读一次，供非 Composable 场景（Service / 组件）复用。 */
fun readDjEnabled(context: Context): Boolean = try {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean(KEY_CROSSFADE_DJ_ENABLED, false)
} catch (_: Throwable) {
    false
}

/**
 * DJ 键 / 跳节拍键的统一入口。
 *
 * 关闭时返回 null —— `PlayerBottomActions` 里两处都是 `if (onDjTrigger != null)` 才渲染，
 * 所以传 null 就是"布局退回四键"，不会留下任何空位或占位。
 */
fun djActionsOrNull(djEnabled: Boolean): (() -> Boolean)? = if (djEnabled) {
    {
        (com.sdw.music.player.MusicService.instance?.triggerCrossfadeNowDetailed()
            ?: com.sdw.music.player.NO_SERVICE_REASON).isEmpty()
    }
} else {
    null
}

/**
 * 【V8.362】DJ 模式走带「切歌下一曲」键的统一行为 = DJ 切歌：
 * 先走一次 DJ 交叉切歌（triggerCrossfadeNow）；只有当这次点击**没有被排队**
 * （lastManualTriggerQueued=false，即不是"过渡中稍后自动补切"）且失败了
 * （暂停中 / 无曲目 / 队列空等）才回退普通硬切 playNext，保证切歌键永远可用。
 * 服务未连接时同样直接回退。排队成功时什么都不做 —— 过渡落点会自动补切。
 */
fun djCutOrFallbackNext(hardNext: () -> Unit): () -> Unit = {
    val svc = com.sdw.music.player.MusicService.instance
    val cut = svc?.triggerCrossfadeNow() == true
    if (!cut && svc?.lastManualTriggerQueued != true) hardNext()
}

const val KEY_CROSSFADE_DJ_ENABLED = "crossfade_dj_enabled"
