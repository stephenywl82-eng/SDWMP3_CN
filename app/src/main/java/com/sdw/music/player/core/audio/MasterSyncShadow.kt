package com.sdw.music.player

import android.util.Log

/**
 * 【V8.343 Master Sync · 第一步：影子模式】
 *
 * 只做**观测与解算**，不改变任何播放行为 —— 不改入场点、不改过渡时长、不动native。
 * 目的：在真机上积累真实数据，验证「跨曲目 master 相位连续」这件事在数学上是否可行、
 * 解算出的调整量是否落在可用范围内，然后再决定第四步（native 落点交棒）怎么设计。
 *
 * ## 为什么第一步可以不碰音频
 *
 * Master Sync 的定义是：整晚所有曲目锁在同一个 master 相位上。本项目现状是
 * 「每次过渡各自对一次拍」，PLL 只活在过渡窗口内，落点时随引擎一起退役
 * （见 oboe_bridge.cpp retireTimeEngineAtLanding）。
 *
 * 但**相位连续性的绝大部分信息量在过渡开始前就已经确定了**：
 *   1. A 轨此刻在 master 相位的哪一��（可算）
 *   2. B 轨的入场点落在 B 自己的哪一小节（可算，见 pushBEntryBeatPhase）
 *   3. 落点时 B 的这一拍对应 master 的哪一拍（可算）
 * → 这三个量足以判断「相位能不能接上」，不需要真的改变音频。
 *
 * ## master 相位的定义
 *
 * 用**累计拍数**（Double）表示 master 走过的绝对拍数，不取模：
 *   masterBeat = Σ (各曲实际播放的拍数)
 * 小节线 = masterBeat % 4 == 0。
 * 累计值的好处是能直接看出「跨了几首、总共漂了多少拍」，比取模后只看相位差更好诊断。
 *
 * ## 已知局限（第二步之前必须记住）
 *
 * ① master BPM 取自**第一首**的BPM，之后不再变（djay 的做法）。若首曲 BPM 测错，
 *    整晚都偏——所以第一步要统计每首的实测 BPM 偏差，评估这个风险有多大。
 * ② 变速范围：keylock 高质量区±8%、PLL 只在 ±3% 内微调。偏离 master 超过 8% 的曲目
 *    实际锁不住，影子模式要统计这类曲目的占比。
 * ③ 单次过渡内 PLL 会持续修正相位，但**落点时随引擎退役**，所以本类算出的
 *    「理论相位」与实际落点相位之间必然有小偏差（8.341 的 `XFADE-SPEED landing residual`
 *    能量化其中一部分）。这一步先看理论值是否站得住。
 */
internal object MasterSyncShadow {

    private const val TAG = "MasterSync"

    /** master BPM（浮点）。取自第一首进入同步的曲目，之后不变。 */
    @Volatile private var masterBpmF: Float = 0f
    @Volatile private var masterBpmSource: String = ""

    /** master 累计拍数（单调递增，不取模）。 */
    private var masterBeats: Double = 0.0
    private var lastUpdateWallMs: Long = 0L

    /** 当前正在计时的曲目（用于按拍累加）。 */
    @Volatile private var curPath: String = ""
    @Volatile private var curBpmF: Float = 0f
    @Volatile private var curStartMasterBeat: Double = 0.0

    /** 统计：跨曲目相位的漂移（拍），用于评估可行性。 */
    @Volatile private var transitionCount: Int = 0
    @Volatile private var phaseErrSumMs: Double = 0.0
    @Volatile private var phaseErrMaxMs: Double = 0.0
    @Volatile private var adjustOutOfRange: Int = 0

    /** master 是否已建立（第一首已定 BPM）。 */
    val ready: Boolean get() = masterBpmF > 0f

    val masterBpm: Float get() = masterBpmF
    val stats: String
        get() = "transitions=$transitionCount avgErrMs=%.1f maxErrMs=%.1f outOfRange=$adjustOutOfRange"
            .format(if (transitionCount > 0) phaseErrSumMs / transitionCount else 0.0, phaseErrMaxMs)

    /**
     * 【P3】真实同步开关。由 MusicService 读 `crossfade_master_sync` 后写入。
     *
     * 默认关闭：打开后过渡时长会被微调（最多 ±1 拍），使落点尽量落在 master 小节线上。
     * 之所以做成开关而不是直接生效，是因为这一步会实际改变听感（过渡变长/变短一点），
     * 必须先能 AB 对比、且随时可退回影子模式。
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * 【P3】建议把过渡时长平移多少毫秒，才能让落点落在 master 的小节线上。
     *
     * 与 `solvePhase` 的区别：solvePhase 只观测不干预（影子），这个返回可直接施加的修正量。
     *
     * 推导（与 solvePhase 同口径，速度 1.0 近似）：
     *   触发即此刻 → master 拍数 M0 = masterBeatsNow()
     *   过渡耗时 xfadeMs → master 再前进 xfadeMs / masterBeatMs 拍
     *   落点拍数 L = M0 + xfadeMs / masterBeatMs，希望 L ≡ 0 (mod 4)
     * 误差 e 归一到 (-2, 2]，理想修正量 = -e × masterBeatMs 毫秒。
     * 最后量化到 A 轨整拍并夹在 ±1 拍内 —— 否则过渡长度会有肉眼可闻的变化，
     * 而 ±1 拍落在 8 小节过渡里只有约 2.5%，听感上察觉不到。
     *
     * @param xfadeMs 本次过渡时长
     * @param aBeatMs A 轨拍长（用于量化与限幅）
     * @return 要加到过渡时长上的毫秒数（0 = 未开启 / master 未就绪 / 无需修正）
     */
    @Synchronized
    fun suggestDurationAdjustMs(xfadeMs: Int, aBeatMs: Double): Int {
        if (!enabled) return 0
        if (masterBpmF <= 0f || xfadeMs <= 0) return 0
        if (aBeatMs <= 0.0) return 0
        val masterBeatMs = 60_000.0 / masterBpmF
        val landingBeat = masterBeatsNow() + xfadeMs / masterBeatMs
        var e = landingBeat % 4.0
        if (e > 2.0) e -= 4.0
        if (e <= -2.0) e += 4.0
        val idealMs = -e * masterBeatMs
        val nBeats = Math.round(idealMs / aBeatMs).toInt().coerceIn(-1, 1)
        val adj = (nBeats * aBeatMs).toLong().toInt()
        Log.i(
            TAG,
            "P3 adjust xf=%dms landingBeat=%.3f err=%+.3f beat ideal=%+.1fms -> %+dms (%+d beat)"
                .format(xfadeMs, landingBeat, e, idealMs, adj, nBeats)
        )
        return adj
    }

    // ------------------------------------------------------------------
    // 时钟维护
    // ------------------------------------------------------------------

    /** 某首曲开始播放：锁定 master BPM（首曲）或加入已有 master。 */
    @Synchronized
    fun onSongStart(path: String, bpmF: Float, beatMsAtZero: Double) {
        val now = System.currentTimeMillis()
        flushTo(now)

        val valid = bpmF in 40f..220f
        if (masterBpmF <= 0f && valid) {
            // 第一首：定义 master BPM。
            masterBpmF = bpmF
            masterBpmSource = path
            Log.i(TAG, "MASTER LOCKED bpm=%.3f src=${shortName(path)}".format(bpmF))
        } else if (valid && masterBpmF > 0f) {
            // 后续曲目：偏离 master 太多就锁不住（keylock 高质量区 ±8%）。
            val dev = (bpmF - masterBpmF) / masterBpmF * 100.0
            if (kotlin.math.abs(dev) > 8.0) {
                Log.w(TAG, "MASTER SKIP bpm=%.2f deviates %.1f%% from master %.2f (>8%% lock window)"
                    .format(bpmF, dev, masterBpmF))
            }
        }

        curPath = path
        curBpmF = if (valid) bpmF else masterBpmF
        curStartMasterBeat = masterBeats
        lastUpdateWallMs = now
    }

    /** 按经过时间累加拍数（用当前曲目自身的 BPM，变速期间以 1.0 近似）。 */
    @Synchronized
    private fun flushTo(nowWallMs: Long) {
        if (lastUpdateWallMs <= 0L || curBpmF <= 0f) return
        val dt = nowWallMs - lastUpdateWallMs
        if (dt <= 0L) return
        val beatMs = 60_000.0 / curBpmF
        masterBeats += dt / beatMs
        lastUpdateWallMs = nowWallMs
    }

    /** 当前 master 累计拍数。 */
    @Synchronized
    fun masterBeatsNow(): Double {
        flushTo(System.currentTimeMillis())
        return masterBeats
    }

    /**
     * 当前 master 相位（小节内位置0.0~4.0 拍）。
     * 返回 -1 表示 master 未就绪。
     */
    fun masterPhaseBeats(): Double {
        val b = masterBeatsNow()
        if (masterBpmF <= 0f) return -1.0
        val m = b % 4.0
        return if (m < 0) m + 4.0 else m
    }

    /**
     * 【第一步核心】解算：若 B 轨从 [bEntryMs] 入场，落点那一刻 master 相位能对到什么程度。
     *
     * @param bEntryMs   B 轨入场点（B 自己的时间轴）
     * @param bBeatMs    B 轨拍长
     * @param bAnchorMs  B 轨的首拍位置（beatgrid / 缓存首拍）
     * @param xfadeMs    本次过渡时长
     * @return 解算结果；master 未就绪或参数不足时返回 null
     */
    fun solvePhase(
        bEntryMs: Long,
        bBeatMs: Double,
        bAnchorMs: Long,
        xfadeMs: Int
    ): SolveResult? {
        if (masterBpmF <= 0f) return null
        if (bBeatMs <= 0.0 || bAnchorMs < 0L) return null
        if (xfadeMs <= 0) return null

        // 1) 落点时刻的 master 拍号（触发瞬间的 master + 过渡期间的拍数）
        val landingMasterBeat = masterBeatsNow() + xfadeMs / (60_000.0 / masterBpmF)

        // 2) B 轨入场点落在 B 自己的第几拍（0-based，可含小数）
        val bBeatAtEntry = (bEntryMs - bAnchorMs) / bBeatMs

        // 3) 落点时 B 已走到第几拍 → 换算成「master 拍号」
        val bBeatsAtLanding = bBeatAtEntry + xfadeMs / bBeatMs
        val impliedMasterBeat = landingMasterBeat - (bBeatsAtLanding - bBeatAtEntry)

        // 4) 相位误差：impliedMasterBeat 应落在 master 小节线上（整数倍 4）
        val nearestBar = Math.round(impliedMasterBeat / 4.0) * 4.0
        val errBeats = impliedMasterBeat - nearestBar
        val errMs = errBeats * bBeatMs

        // 5) 若要修正到最近的小节线，需要把过渡推迟多少 ms
        //    （推迟一个完整 bar 不现实，只考虑 ±1/2 bar 内的微调）
        val adjustMs = -errMs

        transitionCount++
        phaseErrSumMs += kotlin.math.abs(errMs)
        if (kotlin.math.abs(errMs) > phaseErrMaxMs) phaseErrMaxMs = kotlin.math.abs(errMs)
        // 可用调整余量：A 轨触发点到实际落点之间通常有 remain - dur 的富余，
        // 这里保守地按 ±1/4 bar 判定，超出则记为「本轮无法靠时机修正」
        if (kotlin.math.abs(adjustMs) > bBeatMs * 1.0) adjustOutOfRange++

        val res = SolveResult(
            landingMasterBeat = landingMasterBeat,
            bBeatAtEntry = bBeatAtEntry,
            impliedMasterBeat = impliedMasterBeat,
            errMs = errMs,
            suggestDelayMs = adjustMs,
            feasible = kotlin.math.abs(adjustMs) <= bBeatMs
        )
        Log.i(
            TAG,
            "solve entry=${bEntryMs}ms xf=${xfadeMs}ms | landingMasterBeat=%.3f bBeat@entry=%.3f " +
                "impliedMaster=%.3f err=%.1fms(%+.2f beat) suggestDelay=%+.1fms feasible=%b"
                .format(
                    res.landingMasterBeat, res.bBeatAtEntry, res.impliedMasterBeat,
                    res.errMs, errBeats, res.suggestDelayMs, res.feasible
                )
        )
        return res
    }

    /** 过渡落点时调用：记录本轮结果并输出累计统计。 */
    @Synchronized
    fun onLanding(res: SolveResult?) {
        if (res != null && transitionCount % 5 == 0) {
            Log.i(TAG, "STATS $stats")
        }
    }

    /** 用户点歌（非 DJ 过渡）时调用，master 相位连续性不受影响，仅累加拍数。 */
    fun onPlainPlay(path: String, bpmF: Float, beatMsAtZero: Double) {
        onSongStart(path, bpmF, beatMsAtZero)
    }

    /** 打印完整诊断（供设置页或 adb 手动触发）。 */
    fun dump() {
        Log.i(
            TAG,
            "DUMP locked=$ready masterBpm=%.3f src=%s masterBeats=%.2f phase=%.2f beat | %s"
                .format(
                    masterBpmF, shortName(masterBpmSource),
                    masterBeatsNow(), masterPhaseBeats(), stats
                )
        )
    }

    private fun shortName(p: String): String {
        if (p.isBlank()) return "(none)"
        return p.substringAfterLast('/').substringAfterLast('\\').take(40)
    }

    data class SolveResult(
        /** 落点时刻的 master 累计拍号 */
        val landingMasterBeat: Double,
        /** B 入场点在 B 自己时间轴上的拍号 */
        val bBeatAtEntry: Double,
        /** 落点时 B 的拍位换算成的 master 拍号 */
        val impliedMasterBeat: Double,
        /** 与最近 master 小节线的相位误差（毫秒，正=偏晚） */
        val errMs: Double,
        /** 若要修正到小节线，需把过渡推迟/提前的毫秒数 */
        val suggestDelayMs: Double,
        /** 修正量是否落在 ±1 拍内（可由过渡时机吸收） */
        val feasible: Boolean
    )
}