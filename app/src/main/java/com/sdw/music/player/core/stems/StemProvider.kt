package com.sdw.music.player.core.stems

import android.net.Uri

/**
 * 【V8.256】NeuralMix 分轨过渡插件契约（预留）。
 *
 * 设计目标：主应用 **零体积增加** —— 分离模型与推理运行时全部位于独立插件 APK，
 * 宿主只定义本契约 + 发现/回落逻辑。没有插件时一切行为与现状完全一致（三段频）。
 *
 * ## 插件如何接入（独立 APK 侧）
 * 1. 声明一个 Service，intent-filter action = [StemBusManager.ACTION_STEM_PLUGIN]；
 * 2. 绑定后宿主通过 [StemPluginBinder] 询问就绪状态、拉取 stem 流；
 * 3. 模型/权重由插件自行按需下载到自己的私有目录，宿主不感知。
 *
 * ## Stem 约定
 * - 4 轨：vocals(0) / drums(1) / bass(2) / other(3)，与 djay 的 NeuralMix 分组对齐；
 * - 每轨为独立 PCM 流（与源文件同采样率/声道数），host 侧做 4 总线交叉淡化；
 * - 预计算由 **插件** 在候选窗口内自行完成（宿主只提示候选曲），缓存归插件管理。
 *
 * @see StemBusManager
 */
interface StemProvider {

    /** 就绪检查：这首曲子是否已完成 4 stem 预计算（可在候选窗口被异步填充）。 */
    fun isPrecomputed(songPath: String): Boolean

    /**
     * 请求对指定曲目做预计算（宿主在 Automix 确定下一首时调用，fire-and-forget）。
     * 插件自行判断窗口是否足够，来不及可拒绝（返回 false），宿主回落三段频。
     */
    fun requestPrecompute(songPath: String, sampleRateHint: Int, durationMsHint: Long): Boolean

    /** 打开某轨 stem 的 PCM 流（read 顺序回放）。未就绪返回 null。 */
    fun openStem(songPath: String, stem: StemKind): Uri?

    /** 释放该曲目的 stem 缓存（LRU 策略由插件实现，宿主可建议）。 */
    fun releaseCache(songPath: String)
}

enum class StemKind(val id: Int) {
    VOCALS(0), DRUMS(1), BASS(2), OTHER(3);
    companion object {
        fun fromId(id: Int): StemKind = entries.first { it.id == id }
    }
}
