package com.sdw.music.player.core.audio

import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 【V8.429】Java 侧媒体元数据读取的**单线程闸门**。
 *
 * ## 为什么需要它
 *
 * 崩溃现场（Android 16 / API 36，native SIGABRT，不是 Java 异常，logcat 里没有 FATAL）：
 * ```
 * name:      NDK MediaCodec_
 * signal 6 (SIGABRT)
 * Abort:     'MediaCodec.cpp:6909 CHECK(mActivityNotify == NULL) failed.'
 *   #14 libstagefright.so  MediaCodec::onMessageReceived()
 *   #17 ALooper::loop()
 *   #19 AndroidRuntime::javaThreadShell← Java 侧 MediaCodec 事件线程
 * ```
 *
 * `NDK MediaCodec_` 是 **MediaExtractor 内部**为 demux 而建的 codec 事件线程。
 * 断言失败的含义是：**我们的代码在 codec 的事件线程还在派发异步消息时，
 * 就把这个 extractor/codec 拆掉了**。
 *
 * `MediaExtractor` / `MediaMetadataRetriever` 在 `setDataSource()` 阶段会按容器格式
 * 惰性建一个内部 codec（读 esds/ilst 等需要它），`release()` 时销毁。这个内部 codec 有
 * 自己的 ALooper 消息线程，销毁时框架要求"事件线程已静默"。只要**两个 extractor 在不同
 * 线程上交错 create/release**，就可能：
 * ```
 * 线程A: create(内部 codec 起事件线程) ┐ 交错
 * 线程B:                    release(销毁 codec，事件线程还在派发) → abort
 * ```
 *
 * 本工程这类短命 extractor 散落在 9 处（AudioMetadata / AudioAnalyzer / SongSpecReader /
 * SongStructureAnalyzer / UsbDacManager / UsbDacPlaybackController / LyricUtils / SongRepository），
 * 分别跑在 handler 线程、Dispatchers.IO 池、扫描线程、UI 协程里。切歌 + DJ 过渡 +
 * 媒体库修复会并发触发其中多个 → 撞上这个竞态。两次崩溃栈签名完全一致，且出现在
 * 两个不同 APK 上（10:24 与 17:10 装的包），确认是既有老问题、与近期改动无关。
 *
 * ## 修法
 *
 * 把所有**短命** MediaExtractor / MediaMetadataRetriever 的
 * **创建 → 使用 → release 全生命周期收敛到唯一一条专用线程**，
 * 同一时刻只有一个内部 codec 存在，create/release 天然串行、不会交错。
 * 这是 AOSP 这类 abort 的标准缓解手段。
 *
 * 用法：把原有的 `val ex = MediaExtractor(); try { ... } finally { ex.release() }`
 * 整块塞进 [onProbeThread] { }，语义不变，只是换了执行线程。
 *
 * ## 边界（重要）
 *
 * - 只用于**短命探测**（读采样率/时长/首段样本，读完立刻 release）。
 * - **UsbDacPlaybackController 的长命 extractor 不走这里** —— 它整个播放会话都持有
 *   （`extractor` 字段 + 独立 decodeThread 持续 seek/read），塞进单线程闸门会把闸门
 *   占死到播放结束、反而制造 ANR。它的释放走显式 `releaseResources()`，不属于本竞态。
 * - 有界队列(64) + `CallerRunsPolicy`：堆积时由调用线程自己跑，宁可慢也不丢探测，
 *   更不会因为"排队等一个被占死的闸门"而死锁。
 * - `Dispatchers.IO` 的 64 线程池上阻塞等待是安全的（IO 池就是为阻塞设计的），
 *   且这些探测本来就是同步 API，不额外增加耗时。
 */
object MediaProbe {
    private const val TAG = "MediaProbe"

    /** 进程收尾时置位：不再执行新探测，避免 release 期与 framework 拆卸撞车。 */
    private val shuttingDown = AtomicBoolean(false)

    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(64),
        { r ->
            Thread(r, "media-probe").apply {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                )
            }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    /** 闸门线程的 Thread 引用：用于可重入判断（见 [onProbeThread]）。 */
    private val probeThread: Thread? get() = Thread.currentThread().takeIf { it.name == "media-probe" }

    /**
     * 在唯一的探测线程上执行 [block] 并等待结果。异常原样抛给调用方。
     *
     * [block] 内部**必须**包含 extractor/retriever 的创建与释放，
     * 靠"同一时刻只有一个"来消除并发拆卸。
     *
     * **可重入**：如果当前已经在闸门线程上（例如 [SongSpecReader.parse] 内部调
     * [SongSpecReader.mimeOf]，两者都要过闸门），直接就地执行。
     * 否则单线程队列会排到自己后面 → `Future.get()` 永久阻塞 = 死锁。
     */
    fun <T> onProbeThread(block: () -> T): T {
        if (probeThread != null) return block()   // 已在闸门线程：就地跑，避免自嵌套死锁
        if (shuttingDown.get()) {
            Log.w(TAG, "onProbeThread: shutting down, skip")
            throw IllegalStateException("MediaProbe shutting down")
        }
        return executor.submit(Callable { block() }).get()
    }

    fun shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            Log.i(TAG, "shutdown media probe gate")
            executor.shutdown()   // 不 shutdownNow：让在跑的探测正常收尾
        }
    }
}
