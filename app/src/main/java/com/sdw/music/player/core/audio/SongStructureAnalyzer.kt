package com.sdw.music.player.core.audio

import android.util.Log

/**
 * 【V8.386】歌曲段落结构分析（纯 Kotlin，输入是 [WaveformCache] 的能量包络）
 *
 * ## 为什么不用 native 的 preScan 算尾奏
 * 原来的 `preScanTailQuietMs`（oboe_bridge.cpp:29512）会 **新建一个 MediaCodec 解码尾部、
 * 扫完即弃**；preScan 还有第二个 mode（拍点相位），于是**每首歌在切歌前后额外产生
 * 1~2 个一次性解码器**。20 刀连点就会多出 20~40 个 codec —— 这是 DJ 模式
 * 「切几首后交叉淡化全部退化成硬切」的 MediaCodec 耗尽来源之一（见 V8.385 / task #85 #86）。
 *
 * 而尾奏检测真正需要的只是「尾部能量在哪儿塌下去」，这个信息
 * [WaveformCache] 的整曲能量包络里**已经有了**，而且它只用 MediaExtractor、不建 codec。
 *
 * ## 算法口径（阈值已用合成包络做过 5 组验证：标准/偏轻母带/带抖动/全曲高能/超长尾奏）
 * 全部阈值都**相对于包络中位数**（而不是固定绝对值），否则整体偏轻的母带会全判成安静、
 * 整体偏响的会全判成不安静。绝对值只作为上限约束。
 *
 * ## 宁可漏报也不错报
 * 检不出就返回 -1，交给既有的时间阶梯兜底。给一个错的锚点会让过渡切在奇怪的位置，
 * 比不切更糟。
 */
object SongStructureAnalyzer {

    private const val TAG = "SongStruct"

    /** 尾部安静段长度约束：短于 3s 是句尾呼吸，长于 30s 不是可用的 Mix-Out 锚点。 */
    private const val TAIL_MIN_MS = 3_000L
    // 【V8.432 实测】原来 30_000L 太宽。实测《无人之岛》检出 280344ms（曲长 285234ms），
    // 距歌尾仅 4.9s —— 过渡时长 6.9s 根本跨不过去，表现就是"过渡压在歌尾"。
    // 收窄到 12s：尾部安静超过 12s 的，要么是长尾音/现场版，要么是门禁噪声，本来就
    // 不适合当过渡锚点。宁可检出失败退回 RMS 兜底，也不要给一个贴歌尾的假锚点。
    private const val TAIL_MAX_MS = 12_000L

    // 【V8.432 实测】出拍点离歌尾的最小距离。
    // 实测《无人之岛》tailQuiet=280344 /曲长 285234 → 只剩 4890ms，
    // 而过渡要 6906ms —— 过渡起点必然落在"过渡还没结束就已到歌尾"的位置。
    // 判据本身没错（尾部确实安静了），但**作为过渡锚点不可用**。
    // 要求 >= 20s：过渡能完整落在最后一段高能量区里，这才是 djay 的做法。
    private const val TAIL_MIN_HEADROOM_MS = 20_000L
    /** 【V8.430 进拍】intro 段长度约束：短于 2s 只是句首起手，长于 20s 不是 intro 更可能是前奏铺陈。 */
    // 【V8.432 实测调宽】原来 2000ms。实测《无人之岛》"头部安静仅 0ms"——
    // 现代流行曲**第一个采样桶就是满能量**（kick 从0:00 进），根本没有安静前奏，
    // 于是这条判据在当代母带下几乎永远 -1，那条琥珀色的线基本画不出来。
    // 降到 250ms：只要求"开头有一小段比p25 低"，用来定位**旋律/主音进入**的位置，
    // 不再要求"一段安静的前奏"。上限 20s 保持不变（超过就是前奏铺陈，不是可靠锚点）。
    private const val INTRO_MIN_MS = 250L
    // 【V8.433 实测修正】原来 20_000L。实测日志："introQuiet: 头部安静 >20000ms，放弃" ——
    // 注意这**不是"没有 intro"，而是"intro 太长"**：开头 20+ 秒一直低于 p25（慢歌的前奏铺陈）。
    // 20s 上限恰好把大部分慢歌都排除了。而 30~45s 的前奏完全能用来定位"旋律进入点"。
    // 放宽到 45s：判据的本质是"这里有个能量台阶"，与台阶前铺陈多长无关。
    private const val INTRO_MAX_MS = 45_000L

    /** Drop 高能段需稳住这么久才算真 drop（滤掉鼓点瞬时冲高）。 */
    // 【V8.432 实测调宽】原来 8000ms。现代母带（Loudness War + 压缩）把整首歌的能量
    // 压成一条"一直在线"的带子，"持续 8s 高于 p75" 这种条件几乎必然落空 ——
    // 实测《无人之岛》drop 直接 -1，于是入场点没有任何选择余地。
    // 改成 2500ms：仍要求"这是一个段落而不是一次鼓点"，但能被真实歌检出。
    private const val DROP_MIN_MS = 2_500L

    /** Drop 起点不得早于全曲该比例（太靠前通常是前奏而不是 drop）。 */
    private const val DROP_MIN_POS_RATIO = 0.18f

    /** 安静阈值 = 本曲 p25 分位。 */
    private const val QUIET_P = 0.25f
    /** Drop 判据：先跌破 p30，再升到 p75 并稳住 [DROP_MIN_MS]。 */
    private const val DROP_DIP_P = 0.30f
    private const val DROP_HIGH_P = 0.75f

    /**
     * 【V8.386 实测修正】动态范围下限 = p85 - p20。
     *
     * 真机实测（KuwoMusic 抓取、已做响度归一化的曲库）包络是
     * `p05=0.511 p50=0.515 p98=0.520` —— **1400 个桶动态只有 ±2%**，
     * 整首歌被压成一条直线。这种数据没有段落结构可言，
     * 必须**明确放弃并回落原生 preScan**，绝不能给一个假锚点。
     */
    private const val MIN_DYNAMIC_RANGE = 0.06f

    // 【V8.433 实测】"极平"的判据（用于区分"母带被压平"与"本身就是直线型编曲"）。
    // 实测 I Am Optimus Prime：p05=0.496 p50=0.512 p98=0.531 → dyn=0.015。
    // 这不是母带压扁，而是**EDM / DJ 混音的常态**：从头到尾能量一致，本来就没有段落。
    // 对这类歌，"检出进拍/出拍/Drop"在物理上无解，正确做法是让**调用方**降级到虚拟落点，
    // 而不是让这里返回 -1 之后上层回退到"压在歌尾"。
    // 所以这里只把<0.01 视为真正不可用（连量化噪声都不如），0.01~0.06 放行给上层降级处理。
    private const val MIN_DYNAMIC_RANGE_FLOOR = 0.01f

    /** 平滑窗口（桶）。5 桶 ≈ 0.9s：去掉抖动又不至于抹平段落边界。 */
    private const val SMOOTH_BUCKETS = 5

    /**
     * 【V8.446 人声检测】双水位最小台阶差（比值域0~1）。
     * 低于它说明这首歌里"器乐前奏"和"有人声"的中高频占比没有区分度，
     * 强行判定只会检出随机拐点。
     */
    private const val VOCAL_MIN_STEP = 0.04f

    /**
     * 【V8.446 人声检测】越过高频水位后需连续保持的桶数。
     * 7 桶 ≈ 1.2s：一次镲片/一次 pad 抬升不算人声进场。
     */
    private const val VOCAL_HOLD_BUCKETS = 7

    /** 滑动平均。去抖是必须的：单点分位数当硬阈值会被噪声击穿（实测 p85 与段落电平只差 0.006）。 */
    private fun smooth(env: FloatArray, win: Int = SMOOTH_BUCKETS): FloatArray {
        val n = env.size
        if (n < win * 2) return env
        val h = win / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val a = maxOf(0, i - h)
            val b = minOf(n, i + h + 1)
            var s = 0f
            for (j in a until b) s += env[j]
            out[i] = s / (b - a)
        }
        return out
    }

    /** 分位数。内部先排序（1400 个 float，代价可忽略），调用方按需复用已排序数组。 */
    private fun quantileOf(sorted: FloatArray, p: Float): Float {
        if (sorted.isEmpty()) return 0f
        val k = (sorted.size - 1) * p
        val f = k.toInt()
        val c = minOf(f + 1, sorted.size - 1)
        val frac = k - f
        return sorted[f] + (sorted[c] - sorted[f]) * frac
    }

    /**
     * 【V8.430 新增·进拍（Intro）】返回**主旋律真正开始**的绝对毫秒，未检出 -1。
     *
     * 用途：① 界面上画出来（对齐 djay 的两条白线），② 让 B 轨进场能避开前奏铺陈。
     *
     * 判据：从开头往后，跳过**连续**低于本曲 p25 的桶（即 tailQuietMs 的镜像逻辑），
     * 取这段安静的**结束**位置。跟出拍用同一个 [QUIET_P] 阈值，口径一致。
     *
     * 为什么镜像 tailQuietMs 就够：intro 和 outro 的物理性质相同（编曲把能量收/放），
     * 差别只在方向。而连续性要求同样关键 —— intro 里一次鼓点就该判定「旋律进来了」。
     *
     * 约束：安静段必须落在 [INTRO_MIN_MS, INTRO_MAX_MS]，否则不可靠（见 class 注释的
     *「宁可漏报也不错报」）。
     */
    fun introQuietMs(env: FloatArray?, durationMs: Long): Int {
        if (env == null || env.size < 32 || durationMs <= 0) return -1
        val e = smooth(env)
        val sorted = e.copyOf().apply { sort() }
        val dyn = quantileOf(sorted, 0.85f) - quantileOf(sorted, 0.20f)
        // 【V8.433 实测】阈值从 MIN_DYNAMIC_RANGE(0.06) 降到 FLOOR(0.01)：
        // 0.01~0.06 之间的歌（实测 dyn=0.015）是 **EDM/DJ 混音的常态**，不是母带压扁，
        // 放行让上层降级到虚拟落点；返回 -1 会让上层落回"压在歌尾"。
        if (dyn < MIN_DYNAMIC_RANGE_FLOOR) {
            Log.i(TAG, "introQuiet: 动态仅 %.3f <%.2f（母带被压平），放弃 dur=%dms"
                .format(dyn, MIN_DYNAMIC_RANGE, durationMs))
            return -1
        }
        val thr = quantileOf(sorted, QUIET_P)
        val bucketMs = durationMs.toDouble() / env.size
        val cap = (INTRO_MAX_MS / bucketMs).toInt().coerceIn(4, e.size)
        var i = 0
        var run = 0
        while (i < e.size && e[i] <= thr && run < cap) { i++; run++ }
        // 撞到上限仍是安静 → 前奏铺陈太长，不是可靠的「旋律起点」
        if (run >= cap && i < e.size && e[i] <= thr) {
            Log.i(TAG, "introQuiet: 头部安静 >${INTRO_MAX_MS}ms，放弃（dur=$durationMs）")
            return -1
        }
        // 整首都很安静 → 根本没检出 intro（此时 i == e.size）
        if (i >= e.size) return -1
        val quietMs = run * bucketMs
        if (quietMs < INTRO_MIN_MS) {
            Log.i(TAG, "introQuiet: 头部安静仅 ${quietMs.toInt()}ms，太短，视为无 intro（dur=$durationMs）")
            return -1
        }
        val at = i * bucketMs.toLong()
        Log.i(TAG, "introQuiet: dur=${durationMs}ms dyn=%.3f thr(p%.0f)=%.3f -> melody starts %d ms (quiet=${quietMs.toInt()}ms)"
            .format(dyn, QUIET_P * 100, thr, at))
        return at.toInt()
    }

    /**
     * 【V8.446 新增·人声进歌点】返回**人声第一次进来的**绝对毫秒，未检出 -1。
     *
     * ## 为什么需要这个（用户反馈："很多歌进歌点不是人声进歌，都是前旋律进"）
     *
     * [introQuietMs] 用的是 kick（200Hz 低通）包络找"能量台阶"。鼓组是节奏骨架，
     * 前奏铺陈里**第一个鼓点**就会让能量跳起来 —— 于是检出点落在 0:0x，
     * 也就是"前奏第一个鼓"，不是用户要的人声入场。
     *
     * ## 判据：mid/high 占比的**持续抬升**，而不是绝对能量
     *
     * 人声的能量集中在中频（~300Hz~4kHz）。纯器乐前奏的特征是
     * **低频占比高、中高频占比低**（鼓 + 贝斯 + 铺底 pad）；人声一进来，
     * mid 相对 low 的比例会**台阶式上升**并保持。
     *
     * 所以用 `ratio = (mid + high) / (low + mid + high)` 作为人声存在度曲线，
     * 找它从"前奏水位"抬升到"有人声水位"的那个拐点。
     *
     * 为什么用比值而不是 mid 绝对能量：绝对能量会被整首的 Loudness War 压平
     * （用户曲库实测宽带 dyn 只有 0.015），而**比值是相对的**，
     * 前后配平会同时作用于分子分母，不受影响。
     *
     * @param bands WaveformCache.bands3 的 (low, mid, high)，长度必须一致
     * @return 人声入场毫秒；检不出返回 -1
     */
    fun introVocalMs(
        bands: Triple<FloatArray, FloatArray, FloatArray>?,
        durationMs: Long,
        harm: FloatArray? = null
    ): Int {
        if (bands == null || durationMs <= 0) return -1
        val lo = bands.first
        val mid = bands.second
        val hi = bands.third
        if (lo.size < 64 || lo.size != mid.size || lo.size != hi.size) return -1

        // 1) 人声存在度：中高频占比 × 谐波度
        //
        // 【V8.472】只用占比是不够的 —— 详见 WaveformCache.harmonicity() 的注释。
        // 一句话：占比只反映"低频占多少"，前奏独奏乐器（无鼓无贝斯）的占比
        // 比人声主歌**还高**，于是"第一个旋律音"被当成"人声第一拍"。
        // 乘上谐波度（持续周期性）才能把衰减性旋律乐器压下去。
        val n = lo.size
        val ratio = FloatArray(n)
        val hS = if (harm != null && harm.size == n) smooth(harm, 7) else null
        for (i in 0 until n) {
            val l = lo[i]
            val m = mid[i]
            val h = hi[i]
            val tot = l + m + h
            // 分母退化（极端静默/量化噪声）时给 0，不参与后续判定
            val share = if (tot > 1e-6f) (m + h) / tot else 0f
            ratio[i] = if (hS != null) {
                // 软门：谐波度低时不归零（那样纯器乐就永远检不出"主旋律"了），
                // 只是把分数压到 0.2~1.0 区间，让人声段明显高出一截。
                share * (0.20f + 0.80f * hS[i])
            } else share
        }
        if (hS == null) {
            Log.i(TAG, "introVocal: 无谐波度通道（旧缓存），退化为纯频段占比判据")
        }

        // 2) 平滑（桶级平滑窗比其他两处更宽：人声入场判断需要更稳，
        //    一次镲片不能算人声，一次pad 也不能）
        val e = smooth(ratio, 7)

        // 3) 双水位：前奏水位 = 前段 p30，有人声水位 = 全曲 p65
        val headN = (n * 0.18f).toInt().coerceIn(8, n / 3)
        val headSorted = e.copyOf(headN).apply { sort() }
        val quietLevel = quantileOf(headSorted, 0.30f)
        val allSorted = e.copyOf().apply { sort() }
        // 全曲 p65 作为"确认有人声"的水位。取p65 而不是 p75：
        // 典型流行曲人声段落约占全曲 40~70%，p65 更贴近"人声刚进来"的水平；
        // p75 会把标准偏低、要求整曲 1/4 都得有主唱才认。
        val vocalLevel = quantileOf(allSorted, 0.65f)
        // 两水位必须拉开，否则"人声"和"器乐"在这首歌里没有区分度
        if (vocalLevel - quietLevel < VOCAL_MIN_STEP) {
            Log.i(TAG, "introVocal: 双水位过近 (quiet=%.4f vocal=%.4f step=%.4f<%.4f)，判为无清晰人声段"
                .format(quietLevel, vocalLevel, vocalLevel - quietLevel, VOCAL_MIN_STEP))
            return -1
        }
        // 台阶高度取两者中间：要求越过中位，避免只"稍微"高一点就算人声
        val thr = (quietLevel + vocalLevel) * 0.5f

        val bucketMs = durationMs.toDouble() / n
        // 人声最晚不超过全曲 55%（超过多半是副歌重复段，不是首次入场）
        val cap = ((durationMs * 0.55).toDouble() / bucketMs).toInt().coerceIn(8, n)

        // 4) 找第一个「越过 thr 且连续 VOCAL_HOLD_BUCKETS 桶都保持」的拐点。
        //    【V8.473】跳过开头 2 秒：真机实测出现过 "vocals in 0 ms"，
        //    那是第 0 桶就压过阈值（前奏一上来就有能量），落点 0 = 从头播，
        //    对用户毫无意义。人声（哪怕清唱开场）也几乎不可能在 0ms 就进来，
        //    所以直接从 2s 之后开始找 — 找不到就老实回落，别给一个假的人声点。
        var i = (2000.0 / bucketMs).toInt().coerceAtMost(cap - 1).coerceAtLeast(0)
        while (i < cap) {
            if (e[i] > thr) {
                // 检查是否稳住
                val end = (i + VOCAL_HOLD_BUCKETS).coerceAtMost(n)
                var held = true
                for (j in i until end) {
                    if (e[j] <= thr) { held = false; break }
                }
                if (held) {
                    val at = i * bucketMs.toLong()
                    // 吸附到最近的小节边界（BPM 已知时），让进场落在拍上而不是桶边界
                    val snapped = snapToBeat(at, bpmF = 0f, bucketMs = bucketMs)
                    Log.i(TAG, "introVocal: dur=${durationMs}ms quiet=%.4f vocal=%.4f -> vocals in %d ms (snapped=%d)"
                        .format(quietLevel, vocalLevel, at, snapped))
                    return snapped.toInt()
                }
            }
            i++
        }
        Log.i(TAG, "introVocal: 全曲前 55% 未检出稳定人声台阶 (quiet=%.4f vocal=%.4f thr=%.4f)"
            .format(quietLevel, vocalLevel, thr))
        return -1
    }

    /** 吸附到最近的小节线；BPM 未知时退化为吸附到桶边界（= 原值）。 */
    private fun snapToBeat(ms: Long, bpmF: Float, bucketMs: Double): Long {
        if (bpmF <= 1f) return ms
        val beatMs = 60000.0 / bpmF
        val barMs = beatMs * 4.0
        return (Math.round(ms / barMs) * barMs).toLong()
    }


    /**
     * 尾奏起点（Mix-Out 候选点，即 djay 的 "Track A Outro 结束点"）。返回绝对毫秒，未检出 -1。
     *
     * 判据：从结尾往前，跳过**连续**低于本曲 p25 的桶；这段就是尾奏，取其起点。
     * 连续性很关键——中间任何一次鼓点都会打断，这样才不会在鼓点密集的尾奏里误判。
     */
    fun tailQuietMs(env: FloatArray?, durationMs: Long): Int {
        if (env == null || env.size < 32 || durationMs <= 0) return -1
        val e = smooth(env)
        val sorted = e.copyOf().apply { sort() }
        val dyn = quantileOf(sorted, 0.85f) - quantileOf(sorted, 0.20f)
        // 【V8.433 实测】阈值从 MIN_DYNAMIC_RANGE(0.06) 降到 FLOOR(0.01)：
        // 0.01~0.06 之间的歌（实测 dyn=0.015）是 **EDM/DJ 混音的常态**，不是母带压扁，
        // 放行让上层降级到虚拟落点；返回 -1 会让上层落回"压在歌尾"。
        if (dyn < MIN_DYNAMIC_RANGE_FLOOR) {
            Log.i(TAG, "tailQuiet: 动态仅 %.3f <%.2f（母带被压平），放弃 dur=%dms"
                .format(dyn, MIN_DYNAMIC_RANGE, durationMs))
            return -1
        }
        val thr = quantileOf(sorted, QUIET_P)
        val bucketMs = durationMs.toDouble() / env.size
        val cap = (TAIL_MAX_MS / bucketMs).toInt().coerceIn(8, e.size)
        var i = e.size - 1
        var run = 0
        while (i >= 0 && e[i] <= thr && run < cap) { i--; run++ }
        // 撞到上限仍是安静 → 尾奏太长，不是可靠锚点
        if (run >= cap && i >= 0 && e[i] <= thr) {
            Log.i(TAG, "tailQuiet: 尾部安静 >${TAIL_MAX_MS}ms，放弃（dur=$durationMs）")
            return -1
        }
        val quietMs = run * bucketMs
        if (quietMs < TAIL_MIN_MS) {
            Log.i(TAG, "tailQuiet: 尾部安静仅 ${quietMs.toInt()}ms，太短，放弃（dur=$durationMs）")
            return -1
        }
        val at = ((i + 1) * bucketMs).toLong().toInt()
        // 【V8.432】离歌尾太近 → 作为过渡锚点不可用（过渡会跨不过去）。
        val headroom = durationMs - at
        if (headroom < TAIL_MIN_HEADROOM_MS) {
            Log.i(TAG, "tailQuiet: $at ms 距歌尾仅 ${headroom}ms (<${TAIL_MIN_HEADROOM_MS}ms) -> 放弃作锚点")
            return -1
        }
        Log.i(TAG, "tailQuiet: dur=${durationMs}ms dyn=%.3f thr(p%.0f)=%.3f -> $at ms (quiet=${quietMs.toInt()}ms headroom=${headroom})"
            .format(dyn, QUIET_P * 100, thr))
        return at
    }

    /**
     * Drop 起点（Mix-In 候选点）。返回绝对毫秒，未检出 -1。
     *
     * 判据：全曲靠后位置「先跌破本曲 p30，随后升到 p75 并持续 [DROP_MIN_MS]」，
     * 取「跌破」那个桶作为 drop 起点（能量爆发的入场点）。
     * @param bpmF 浮点 BPM；给到时把结果吸附到小节线，供 Automix 做乐句级对齐。
     */
    fun dropMs(env: FloatArray?, durationMs: Long, bpmF: Float = 0f): Int {
        if (env == null || env.size < 64 || durationMs <= 0) return -1
        val e = smooth(env)
        val sorted = e.copyOf().apply { sort() }
        val dyn = quantileOf(sorted, 0.85f) - quantileOf(sorted, 0.20f)
        // 【V8.433 实测】阈值从 MIN_DYNAMIC_RANGE(0.06) 降到 FLOOR(0.01)：
        // 0.01~0.06 之间的歌（实测 dyn=0.015）是 **EDM/DJ 混音的常态**，不是母带压扁，
        // 放行让上层降级到虚拟落点；返回 -1 会让上层落回"压在歌尾"。
        if (dyn < MIN_DYNAMIC_RANGE_FLOOR) {
            Log.i(TAG, "drop: 动态仅 %.3f <%.2f，放弃 dur=%dms".format(dyn, MIN_DYNAMIC_RANGE, durationMs))
            return -1
        }
        val dipThr = quantileOf(sorted, DROP_DIP_P)
        val highThr = quantileOf(sorted, DROP_HIGH_P)
        if (highThr <= dipThr) return -1          // 动态范围太平坦，分不出 drop

        val bucketMs = durationMs.toDouble() / env.size
        val fromIdx = (e.size * DROP_MIN_POS_RATIO).toInt().coerceIn(1, e.size - 1)
        val needBuckets = (DROP_MIN_MS / bucketMs).toInt().coerceAtLeast(4)

        var idx = fromIdx
        while (idx < e.size - 1) {
            if (e[idx] > dipThr) { idx++; continue }
            val dip = idx
            var rise = dip
            while (rise < e.size && e[rise] < highThr) rise++
            if (rise >= e.size) break
            var stay = rise
            while (stay < e.size && e[stay] >= highThr) stay++
            val heldMs = (stay - rise) * bucketMs
            if (heldMs >= DROP_MIN_MS) {
                val raw = (dip * bucketMs).toLong()
                val snapped = snapToBar(raw, bpmF)
                Log.i(TAG, "drop: dur=${durationMs}ms dip=$dip held=${heldMs.toInt()}ms " +
                    "-> $raw ms, snapped=$snapped")
                return snapped
            }
            idx = (dip + needBuckets).coerceAtLeast(dip + 1)
        }
        return -1
    }

    /**
     * 【#2】真实段落 / 乐句结构检测：返回整曲的**分段边界序列**（verse→chorus→bridge 等）。
     *
     * 与已有的单点锚（introQuiet/drop/chorus/tailQuiet）不同，这里是**连续分段**，
     * 落点选择可据此把交叉淡化切在"音乐换段处"而非"算出来的 4/4 小节线"。
     *
     * ## 输入与成本
     * 直接吃 [WaveformCache] 已有的能量包络（与 tailQuiet/drop 同源），可选吃 dropMs 提示。
     * **不建任何 MediaCodec**（沿用 V8.386 的零解码器原则），只在既有包络上算。
     *
     * ## 算法
     * 1) 平滑包络下采样成 ~1.5s 粗能量桶，再平滑一次去掉乐句级抖动；
     * 2) 滞后双水位（lowThr/highThr = 全曲 p35/p65）做**电平分段**：
     *    上穿 highThr = 段落变响（verse→chorus），下穿 lowThr = 段落变轻（chorus→verse/outro），
     *    即换段边界；
     * 3) 要求新电平**持续 ≥ MIN_SEG**（太短只是乐句转身，不是换段）；相邻边界 < MIN_GAP 合并；
     * 4) 每个边界吸附到最近小节线（bpmF 已知时复用 [snapToBar]），保证过渡落在拍上；
     * 5) 每段按均值能量打 best-effort 标签：INTRO(首段低能) / OUTRO(末段低能) /
     *    CHORUS(高于 highThr) / DROP(含 dropMsHint) / VERSE(默认)。
     *
     * ## 宁可漏报也不错报（与全工程一致）
     * 动态过平（dyn < [MIN_DYNAMIC_RANGE_FLOOR]）或水位拉不开或边界 < 2 → 返回空列表，
     * 调用方退回"小节线吸附"行为，绝不给假边界。
     *
     * @param env        能量包络（kick 或宽带，与 tailQuiet 同源）
     * @param durationMs 曲长（ms）
     * @param bpmF       浮点 BPM，给到时把边界吸附到小节线
     * @param dropMsHint 已知 Drop 起点（能量爆发入场点），用于把对应段标 DROP
     */
    fun sectionBounds(
        env: FloatArray?,
        durationMs: Long,
        bpmF: Float = 0f,
        dropMsHint: Int = -1,
        snapToGrid: Boolean = true
    ): List<com.sdw.music.player.BpmKeyCache.SectionBoundary> {
        if (env == null || env.size < 64 || durationMs <= 0) return emptyList()
        val e = smooth(env)
        val sorted = e.copyOf().apply { sort() }
        val dyn = quantileOf(sorted, 0.85f) - quantileOf(sorted, 0.20f)
        // 太扁的歌（EDM/DJ 混音/母带压平）没有段落结构，放弃
        if (dyn < MIN_DYNAMIC_RANGE_FLOOR) {
            Log.i(TAG, "sectionBounds: 动态仅 %.3f <%.2f（母带被压平），放弃 dur=$durationMs"
                .format(dyn, MIN_DYNAMIC_RANGE))
            return emptyList()
        }
        val bucketMs = durationMs.toDouble() / env.size
        // 1) 粗能量桶（~1.5s 一桶）
        val winB = maxOf(1, (1500.0 / bucketMs).toInt())
        val nCoarse = (env.size + winB - 1) / winB
        val coarse = FloatArray(nCoarse)
        for (i in 0 until nCoarse) {
            val a = i * winB
            val b = minOf(env.size, a + winB)
            var s = 0f
            for (j in a until b) s += e[j]
            coarse[i] = if (b > a) s / (b - a) else 0f
        }
        // 2) 再平滑一次（去乐句级抖动，只留段落级台阶）
        val cs = smooth(coarse, 3)
        // 3) 滞后双水位电平分段
        val cSorted = cs.copyOf().apply { sort() }
        val lowThr = quantileOf(cSorted, 0.35f)
        val highThr = quantileOf(cSorted, 0.65f)
        if (highThr - lowThr < 0.04f) {
            Log.i(TAG, "sectionBounds: 粗能量双水位过近 (lo=%.4f hi=%.4f)，放弃 dur=$durationMs"
                .format(lowThr, highThr))
            return emptyList()
        }
        val minSegB = maxOf(2, (12_000.0 / (winB * bucketMs)).toInt())   // 段最短 12s
        val minGapB = maxOf(2, (16_000.0 / (winB * bucketMs)).toInt())   // 边界合并间距 16s
        val rawBounds = ArrayList<Int>()
        var level = if (cs[0] >= (lowThr + highThr) / 2f) 1 else 0
        var since = 0
        for (i in 1 until cs.size) {
            val newLevel = when {
                cs[i] >= highThr -> 1
                cs[i] <= lowThr -> 0
                else -> level   // 中间带：保持当前电平（滞后）
            }
            if (newLevel != level) {
                if (i - since >= minSegB) rawBounds.add(i)   // 新电平需持续够久才算换段
                level = newLevel
                since = i
            }
        }
        if (rawBounds.size < 2) {
            Log.i(TAG, "sectionBounds: 仅检出 ${rawBounds.size} 个换段点（<2），放弃 dur=$durationMs")
            return emptyList()
        }
        // 合并过近的边界（保留更靠后的，能量台阶更显著）
        val bounds = ArrayList<Int>()
        bounds.add(rawBounds[0])
        for (i in 1 until rawBounds.size) {
            if (rawBounds[i] - bounds.last() < minGapB) bounds[bounds.lastIndex] = rawBounds[i]
            else bounds.add(rawBounds[i])
        }
        // 4) 边界 → 毫秒，吸附小节线
        // 【#1】低置信度 BPM 不可信：跳过吸附（snapToGrid=false，由调用方按 crossfade_bpm_min_conf
        // 判定后传入），避免用错 BPM 把段落界面对歪；返回未对齐的检出位置同样可被落点逻辑使用。
        val boundsMs = bounds.map { (it.toDouble() * winB * bucketMs).toLong() }
            .map { if (bpmF in 40f..220f && snapToGrid) snapToBar(it, bpmF).toLong() else it }
            .distinct().sorted()
        if (boundsMs.size < 2) {
            Log.i(TAG, "sectionBounds: 吸附后仅 ${boundsMs.size} 个边界，放弃 dur=$durationMs")
            return emptyList()
        }
        // 5) 每段均值能量 → 标签
        val segMeans = ArrayList<Float>()
        for (k in boundsMs.indices) {
            val sMs = boundsMs[k]
            val eMs = if (k + 1 < boundsMs.size) boundsMs[k + 1] else durationMs
            val sa = (sMs / bucketMs).toInt().coerceIn(0, cs.size - 1)
            val eb = (eMs / bucketMs).toInt().coerceIn(0, cs.size - 1)
            var s = 0f; var c = 0
            for (j in sa..eb) { s += cs[j]; c++ }
            segMeans.add(if (c > 0) s / c else 0f)
        }
        val out = ArrayList<com.sdw.music.player.BpmKeyCache.SectionBoundary>()
        for (k in boundsMs.indices) {
            val sMs = boundsMs[k].toInt()
            val eMs = if (k + 1 < boundsMs.size) boundsMs[k + 1].toInt() else durationMs.toInt()
            val mean = segMeans[k]
            val kind = when {
                k == 0 && mean <= lowThr -> com.sdw.music.player.BpmKeyCache.SectionBoundary.INTRO
                k == boundsMs.lastIndex && mean <= lowThr -> com.sdw.music.player.BpmKeyCache.SectionBoundary.OUTRO
                mean >= highThr -> com.sdw.music.player.BpmKeyCache.SectionBoundary.CHORUS
                dropMsHint > 0 && sMs <= dropMsHint && dropMsHint < eMs ->
                    com.sdw.music.player.BpmKeyCache.SectionBoundary.DROP
                else -> com.sdw.music.player.BpmKeyCache.SectionBoundary.VERSE
            }
            out.add(com.sdw.music.player.BpmKeyCache.SectionBoundary(sMs, eMs, kind))
        }
        Log.i(TAG, "sectionBounds: dur=$durationMs bpm=$bpmF -> ${out.size} 段 " +
            out.joinToString { "${it.startMs}/${it.endMs}(${it.kind})" })
        return out
    }

    /** 把毫秒吸附到最近的**小节线**（4/4 拍）。bpmF 非法时原样返回。 */
    fun snapToBar(ms: Long, bpmF: Float): Int {
        if (bpmF < 40f || bpmF > 220f) return ms.toInt()
        val barMs = 4.0 * 60_000.0 / bpmF
        if (barMs <= 1.0) return ms.toInt()
        val snapped = Math.round(ms / barMs) * barMs
        return snapped.coerceAtLeast(0.0).toInt()
    }

    /**
     * 【#3】降拍（beat-1）相位校正：给定 native 首拍 [firstBeatMs] 与 BPM [bpmF]，
     * 用能量包络 [env]（时长 [durationMs]）统计每个「拍内相位 0..3」的小节起始 onset 能量，
     * 能量最强的相位即真 beat-1。返回 native 首拍相对真 beat-1 的拍差 delta（0..3，0=已对齐）。
     * 包络缺失/不可用时返回 0（调用方据此跳过校正，零退化、幂等：已校正后再算 delta 必为 0）。
     */
    fun downbeatDeltaBeats(env: FloatArray?, durationMs: Long, firstBeatMs: Int, bpmF: Float): Int {
        if (env == null || env.size < 64 || durationMs <= 0 || firstBeatMs < 0
            || bpmF < 40f || bpmF > 220f) return 0
        val bucketMs = durationMs.toDouble() / env.size
        if (bucketMs <= 0.0) return 0
        val beatMs = 60000.0 / bpmF
        val barMs = beatMs * 4.0
        if (barMs <= 1.0) return 0
        val phaseE = DoubleArray(4)
        val maxMs = minOf(durationMs.toDouble(), firstBeatMs + 32 * barMs)
        var t = firstBeatMs.toDouble()
        var bars = 0
        while (t < maxMs) {
            for (p in 0..3) {
                val tt = t + p * beatMs
                if (tt >= durationMs) break
                val idx = (tt / bucketMs).toInt().coerceIn(0, env.size - 1)
                phaseE[p] += env[idx].toDouble()
            }
            t += barMs
            bars++
        }
        if (bars < 2) return 0
        var best = 0
        for (p in 1..3) if (phaseE[p] > phaseE[best]) best = p
        return best
    }

    /**
     * 【V8.390】把「理想的过渡启动时刻」量化到**小节线**（4/4 强拍）。
     *
     * ## 为什么需要
     * 原来的触发是纯绝对时间：`if (remain <= dur) 触发`。dur 到了就切，
     * 完全不管那一刻正卡在鼓点正中还是弱拍上 —— 于是两首歌节奏错位，
     * 听起来像两只乐队在各自打架。这就是「不像专业 DJ」的技术根因之一。
     *
     * ## 【关键语义】只能向「更早」取整，绝不向「更晚」
     * 设 dur 为过渡长度、idealStart 为「不量化时的起切时刻」（= 歌尾 - dur）。
     * 若把起切量化到**更晚**的格子，则 量化点 + dur > 歌尾 ⇒
     * **过渡会超出歌曲结尾，结尾被硬切**。
     * 所以：晚于 idealStart 的候选一律拒绝 —— 等价于 `floor`。
     * 越早起切只会让过渡更早结束（安全），代价顶多是与 outro 起点略有偏差。
     *
     * 常见的错误写法是 `round`（就近双向）：8 小节乐句在 110BPM 下长 17.4s，
     * 往后最多偏 8.7s，**足以把过渡整体推出歌尾**。djay 的做法也是只往早的方向取。
     *
     * ## 其余两个保守约定
     * - 数据缺失（firstBeatMs<0 或 BPM 越界）→ 原样返回，退回旧的绝对时间行为。
     * - 偏移过大（早于 idealStart 超过 [maxSnapMs]）→ 放弃量化。
     *   弱拍漂移严重的曲子强行吸到很远的格子反而更糟。
     *
     * @param idealStartMs 理想启动时刻（= 歌尾 - dur，或 outro 起点）
     * @param firstBeatMs  A 轨第一拍（强拍）绝对时间戳
     * @param bpmF         A 轨 BPM（浮点，bpmF 才有亚 BPM 精度）
     * @param maxSnapMs    允许「更早」的最大毫秒数
     * @return 量化后的启动时刻（恒 <= idealStartMs）；不该量化时返回 idealStartMs
     */
    fun quantizeTriggerUpToBar(
        idealStartMs: Long,
        firstBeatMs: Long,
        bpmF: Float,
        maxSnapMs: Long = 2_000L
    ): Long {
        if (idealStartMs <= 0) return idealStartMs
        if (firstBeatMs < 0 || bpmF < 40f || bpmF > 220f) return idealStartMs
        val barMs = 4.0 * 60_000.0 / bpmF
        if (barMs <= 1.0) return idealStartMs
        // floor：只往「更早」的格子取，绝不取比 idealStart 更晚的（那会把过渡推出歌尾）
        val k = Math.floor((idealStartMs - firstBeatMs) / barMs)
        val snapped = firstBeatMs + (k * barMs).toLong()
        if (snapped > idealStartMs) return idealStartMs       // 防御：绝不允许更晚
        if (idealStartMs - snapped > maxSnapMs) return idealStartMs   // 挪太远，放弃量化
        if (snapped < 0L) return idealStartMs
        return snapped
    }

    /**
     * 取曲长（毫秒），失败返回 -1。
     *
     * 用 [MediaMetadataRetriever] 只读元数据 —— **不创建 MediaCodec**。
     * 包络本身只给归一化能量、不含时长，而桶→毫秒的换算必须知道曲长；
     * 原来的 preScan 是靠解码顺带拿到 durationMs 的，这里不能那么干（见类注释）。
     */
    fun durationMsOf(path: String): Long {
        if (path.isBlank()) return -1L
        if (path.startsWith("content://")) return -1L   // 交给调用方走别的路子
        // 【V8.429】收敛到单线程闸门：MediaMetadataRetriever 内部也会建 codec，
        // 与其它探测并发拆卸会触发 native abort（详见 MediaProbe 类注释）。
        return MediaProbe.onProbeThread {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(path)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: -1L
            } catch (_: Throwable) {
                -1L
            } finally {
                try { r.release() } catch (_: Throwable) {}
            }
        }
    }
}
