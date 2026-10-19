package com.sdw.music.player.core.stems
import com.sdw.music.player.core.audio.playback.MusicService

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.sdw.music.player.stem.IStemPlugin

/**
 * 【V8.424 回退】分轨引擎 —— 恢复独立插件 APK + AIDL 绑定（V8.258–V8.412 架构）。
 *
 * V8.423 曾把分离引擎整进宿主进程，实测整包 67MB 且运行不稳，按用户要求回退为
 * 独立插件（com.sdw.stem.plugin，经 action com.sdw.music.player.action.STEM_PLUGIN 绑定）。
 *
 * 绑定策略：懒绑定（首次 probe 才发起，BIND_AUTO_CREATE）。插件进程仍可能被
 * 厂商 DeviceGuard 杀/冻 —— 所有 IPC 调用都包 try-catch，远端异常即置空连接，
 * 返回 false/null → 宿主静默回落三段频，**绝不影响正常切歌**。
 *
 * 对外 API 与 V8.423 进程内版**同名同签名**，MusicService 的全部调用点零改动。
 */
object StemBusManager {

    private const val TAG = "StemBus"
    /**
     * 【V8.428】探测阶段只预计算入场点后的 45 秒（与插件 CHUNK_SECONDS 一致）。
     * 过渡真正用到的音频就在这段里，整曲分离纯属浪费电。
     */
    private const val PROBE_WINDOW_MS = 45_000L
    private const val PLUGIN_PKG = "com.sdw.stem.plugin"
    private const val PLUGIN_ACTION = "com.sdw.music.player.action.STEM_PLUGIN"
    /**
     * 【V8.505】唤醒广播 action。Motorola DeviceGuard 拦截宿主跨 UID 拉起插件服务
     * (bindService 谎报 not found)，故 bind 前先发此广播，由插件同 UID 自启动服务绕过拦截。
     */
    private const val WAKE_ACTION = "com.sdw.music.player.action.STEM_WAKE"

    @Volatile private var plugin: IStemPlugin? = null
    @Volatile private var binding = false
    private val lock = Any()

    // 【V8.474 请求去重】真机取证：同一首歌在 70ms 内被请求了 **3 次** 分轨 ——
    //   15:01:29.411 stem probe  ... requestPrecomputeWindow(0..45000ms) accepted=true
    //   15:01:29.412 stem window requested: ... (0..23648ms)
    //   15:01:38.232 stem window requested: ... (0..45000ms)   ← B 轨上位后 27ms 又来一次
    // 插件每次都要重新打开文件、建 MediaCodec 解 45 秒、再跑神经网络。
    // 三条并发 = CPU / 内存带宽 / IO 同时打满，正是"B 轨过渡后几秒噪音"的来源。
    // 这里按 (path, startMs, windowMs) 去重，窗口内重复请求直接丢弃。
    private val recentReq = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REQ_DEDUP_MS = 60_000L

    private fun dedupKey(path: String, startMs: Long, windowMs: Long) =
        "$path|$startMs|$windowMs"

    /** 返回 true 表示这次请求**应该发出**（未被去重）。 */
    private fun claimRequest(path: String, startMs: Long, windowMs: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = dedupKey(path, startMs, windowMs)
        val last = recentReq[key]
        if (last != null && now - last < REQ_DEDUP_MS) {
            Log.i(TAG, "stem request deduped(跳过): '$path' ($startMs..${startMs + windowMs}ms) "
                + "已在 ${(now - last) / 1000}s 前请求过")
            return false
        }
        recentReq[key] = now
        // 顺手清理过期项，避免 map 无限增长
        if (recentReq.size > 64) {
            val it = recentReq.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value >= REQ_DEDUP_MS) it.remove()
            }
        }
        return true
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            plugin = IStemPlugin.Stub.asInterface(service)
            binding = false
            Log.i(TAG, "stem plugin connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // 插件进程被杀（DeviceGuard / 后台冻结）→ 置空，下次调用重新绑定
            plugin = null
            Log.w(TAG, "stem plugin disconnected (process killed?)")
        }
    }

    /** 插件 APK 是否已安装（旧 hasPlugin 语义：查插件包）。 */
    fun hasPlugin(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PLUGIN_PKG, 0) != null
    } catch (_: Throwable) {
        false
    }

    /**
     * 【V8.505】发唤醒广播让插件自启动（同 UID 绕过 DeviceGuard 跨 UID 启动拦截）。
     * 幂等、fire-and-forget：插件已存活时只是重复自启一次，无副作用。
     */
    private fun wakePlugin(context: Context) {
        try {
            context.sendBroadcast(Intent(WAKE_ACTION).setPackage(PLUGIN_PKG))
        } catch (t: Throwable) {
            Log.w(TAG, "wake broadcast failed: ${t.message}")
        }
    }

    /** 懒绑定：未连接且未在绑定时发起 bindService（BIND_AUTO_CREATE 拉起插件进程）。 */
    private fun ensureBound(context: Context): IStemPlugin? {
        plugin?.let { return it }
        val appCtx = context.applicationContext
        synchronized(lock) {
            plugin?.let { return it }
            if (binding) return null
            if (!hasPlugin(appCtx)) {
                Log.i(TAG, "stem plugin not installed -> 3-band fallback")
                return null
            }
            // 【V8.505】Moto DeviceGuard 拦截宿主跨 UID 拉起插件服务，故先发唤醒广播，
            // 让插件同 UID 自启动服务；随后 bindService 连上已存在的 binder 即可成功。
            wakePlugin(appCtx)
            binding = true
            return try {
                val intent = Intent(PLUGIN_ACTION).setPackage(PLUGIN_PKG)
                val ok = appCtx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                Log.i(TAG, "bindService -> $ok")
                if (!ok) binding = false
                null   // 异步连接，本轮先回落，连上后下轮生效
            } catch (t: Throwable) {
                Log.w(TAG, "bind failed: ${t.message}")
                binding = false
                null
            }
        }
    }

    /** IPC 调用封装：连接为空先懒绑定（本轮返回兜底值），远端异常置空连接触发重绑。 */
    private inline fun <T> withPlugin(context: Context, fallback: T, tag: String, call: (IStemPlugin) -> T): T {
        val p = plugin ?: ensureBound(context) ?: return fallback
        return try {
            call(p)
        } catch (t: Throwable) {
            plugin = null
            Log.w(TAG, "$tag: ${t.message}")
            fallback
        }
    }

    /**
     * 过渡前探测：B 轨是否已有预计算好的 4 stem。
     * 未就绪时同步触发预计算入队（fire-and-forget，引擎自行去重/下载模型/排期）；
     * 仍返回当前是否已就绪（未就绪→本轮回落三段频，下轮再判）。
     *
     * 【V8.428】**不再请求整曲分离**。旧版这里调 `requestPrecompute()`（整曲），
     * 一首 3~5 分钟的歌就是 5~10 分钟 ONNX 全曲推理，插件 CPU 常年 100%+、内存峰值 1.5GB，
     * 是耗电最大头。而宿主真正用到的只有入场点后的几十秒（requestWindow/requestWindowAt 已经
     * 按精确 entryMs 请求过窗口），整曲结果根本没人读。
     * 现在统一只请求 [0, PROBE_WINDOW_MS] 窗口；插件侧 isPrecomputed 只看"有 4 轨 + 有效 meta"，
     * 不区分整曲/窗口，所以窗口缓存同样能让这里返回 true。
     */
    fun probePrecomputed(context: Context, songPath: String, sampleRate: Int, durationMs: Long): Boolean {
        return withPlugin(context, false, "stem probe") { p ->
            val ready = p.isPrecomputed(songPath)
            if (!ready) {
                if (!claimRequest(songPath, 0L, PROBE_WINDOW_MS)) return@withPlugin false
                val accepted = p.requestPrecomputeWindow(songPath, sampleRate, durationMs, 0L, PROBE_WINDOW_MS)
                Log.i(TAG, "stem probe: '$songPath' precomputed=false, requestPrecomputeWindow(0..${PROBE_WINDOW_MS}ms) accepted=$accepted")
            } else {
                Log.i(TAG, "stem probe: '$songPath' precomputed=TRUE (stem bus eligible)")
            }
            ready
        }
    }

    /** 早期规划点调用：请求预计算下一曲的 [0, windowMs] 窗口（fire-and-forget）。 */
    fun requestWindow(context: Context, songPath: String, windowMs: Long) {
        if (!claimRequest(songPath, 0L, windowMs)) return
        withPlugin(context, Unit, "requestWindow") { p ->
            p.requestPrecomputeWindow(songPath, 0, 0, 0L, windowMs)
            Log.i(TAG, "stem window requested: '$songPath' (0..${windowMs}ms)")
        }
    }

    /** 精确窗口请求：以 startMs 为起点存 windowMs（入场点在窗口之后的长曲也能命中）。 */
    fun requestWindowAt(context: Context, songPath: String, startMs: Long, windowMs: Long) {
        if (!claimRequest(songPath, startMs, windowMs)) return
        withPlugin(context, Unit, "requestWindowAt") { p ->
            p.requestPrecomputeWindow(songPath, 0, 0, startMs, windowMs)
            Log.i(TAG, "stem window requested: '$songPath' ($startMs..${startMs + windowMs}ms)")
        }
    }

    /** 过渡触发时：窗口 [entryMs, entryMs+neededMs] 是否已就绪。 */
    fun isWindowReady(context: Context, songPath: String, entryMs: Long, neededMs: Long): Boolean {
        return withPlugin(context, false, "isWindowReady") { p ->
            p.isPrecomputedWindow(songPath, entryMs, neededMs)
        }
    }

    /** 打开 4 条 stem 流（vocals/drums/bass/other），任一缺失返回 null。 */
    fun openStems(context: Context, songPath: String): List<ParcelFileDescriptor>? {
        return withPlugin(context, null, "openStems") { p ->
            val fds = (0 until 4).map { p.openStem(songPath, it) }
            if (fds.any { it == null }) {
                fds.forEach { it?.close() }
                null
            } else {
                fds.map { it!! }
            }
        }
    }
}
