package com.sdw.music.player.core

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 【V8.264】全库分析进度（供设置页显示）。
 * running=false 表示未在强制全速模式（自动模式仍在熄屏充电时低频进行）。
 */
object AnalysisProgress {
    val running = MutableStateFlow(false)
    /** 剩余待分析首数 */
    val stale = MutableStateFlow(0)
    /** 曲库总数 */
    val total = MutableStateFlow(0)
}
