// SDW NeuralMix Stem 插件 AIDL 契约 v2。
// 与插件 APK（SDWStemPlugin app/src/main/aidl/...）保持 package 与方法签名逐字一致。
package com.sdw.music.player.stem;

interface IStemPlugin {
    /** 该曲目是否已完成 4 stem 预计算并缓存可用（整曲模式，v1 遗留）。 */
    boolean isPrecomputed(String songPath);

    /**
     * 请求预计算（fire-and-forget 入队）。模型未就绪 / 磁盘不足返回 false，
     * 宿主据此回落三段频。入队成功返回 true（完成时间由插件自行排期）。
     */
    boolean requestPrecompute(String songPath, int sampleRateHint, long durationMsHint);

    /** 打开某轨 stem 的只读流（裸 PCM **i16le** 立体声交织，4 字节/帧）。未就绪返回 null。
     *  【v0.10】v0.9 及以前是 f32le（8 字节/帧）；格式不符的旧缓存插件会在启动时清除。 */
    android.os.ParcelFileDescriptor openStem(String songPath, int stemId);

    void releaseCache(String songPath);

    // ---------------- v2：过渡窗口局部预计算 ----------------

    /**
     * 该曲目的 [entryMs, entryMs+neededMs] 窗口是否已有 stem 缓存覆盖。
     * 宿主在过渡触发前调用；就绪即可走 stem 总线过渡。
     */
    boolean isPrecomputedWindow(String songPath, long entryMs, long neededMs);

    /**
     * 请求只预计算 [entryMs, entryMs+windowMs] 窗口（大幅提速：不用整曲）。
     * 窗口已覆盖则直接 true；模型未就绪则先下载再算。fire-and-forget。
     */
    boolean requestPrecomputeWindow(String songPath, int sampleRateHint, long durationMsHint,
                                    long entryMs, long windowMs);
}
