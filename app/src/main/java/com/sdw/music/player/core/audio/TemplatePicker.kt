package com.sdw.music.player

import android.util.Log

/**
 * 【V8.346·过渡音效智能选择】
 *
 * ## 为什么需要它
 *
 * 原实现（V8.297~8.310）：
 * ```kotlin
 * "random" -> listOf(1, 2, 3, 4, 5, 6, 8, 9).random()
 * ```
 * **八选一纯随机**，完全不看 A/B 两首歌的 BPM 差、时长、调性、能量。
 * 结果就是用户反馈的「效果随机搭配的不合适」——典型错配：
 *   · BPM 差 20% 时用 Pitch Drop（变调下扫）→ 听起来像唱磁带坏了
 *   · 慢歌 (70 BPM) 用 Beat Roll（1/2 拍切片）→ 节奏被切碎，没有推进感
 *   · 快歌 (150 BPM) 用长 Echo → 糊成一片，听不清新歌进点
 *
 * ## djay 的做法（观察归纳）
 *
 * djay 的 Automix 不做纯随机，它按**两首歌的关系**选效果：
 * 节奏差大 → 用能"藏住"错位的效果（滤波/溶解）；
 * 节奏接近 → 用能强化对拍的效果（tempo blend / roll）；
 * 同调性 → 可用和声类；跨调性 → 避开强调调性的效果。
 * 另外它避免连续重复同一个效果（连续 3 次以上会显得机械）。
 *
 * ## 本类的策略
 *
 * 三步决策，**全部基于已有数据，不新增分析开销**：
 *  ① **硬性排除**：明显不适配的组合直接剔掉（不是降权，是不用）
 *  ② **打分排序**：剩下的按「BPM 差 / 曲长 / 能量差 / 模板自身适配度」打分
 *  ③ **多样性约束**：最近用过的模板降权，避免连续重复
 *
 * 仍然保留随机性（同分时随机），但**随机发生在"合适的候选"之间**，
 * 而不是在"所有模板"之间 —— 这是与原实现本质的区别。
 *
 * ## 为什么不做得更"确定"
 *
 * 完全确定（每对歌曲固定一种效果）会让整个 set 听起来像被编程过。
 * DJ 的乐趣之一就是每次不一样。所以这里只在**明显不合适时排除**，
 * 其余交给打分 + 少量随机。
 */
internal object TemplatePicker {

    private const val TAG = "TplPicker"

    // 模板号（与 native g_xfadeTpl 的取值一致）
    const val T_FILTER = 1      // Filter Fade：滤波淡出
    const val T_ECHO = 2        // Echo Fade：回声淡出
    const val T_BRAKE = 3       // Brake：刹车（切碎 + 停顿感）
    const val T_DISSOLVE = 4    // Dissolve Wash：溶解
    const val T_TEMPO = 5       // Tempo Blend：变速对拍
    const val T_ROLL = 6        // Beat Roll：拍点切片
    const val T_PHRASE = 8      // Phrase：按小节分段
    const val T_PITCH = 9       // Pitch Drop：变调下扫
    const val T_EQ = 10         // EQ Crossfade：三段 EQ 交叉（低频中点交换，中高频等功率）
    const val T_STEM = 11        // 【Phase1】Neural Mix 声部交叉：自动模式下分轨可用时的高优先级子模板

    // 【V8.414】按用户反馈移除 T_FILTER(滤波交接) 与 T_PITCH(变调下滑)：实测听感是白噪音，太难听，
    // 从自动选择候选池里一并剔除（random 模板下也不能再随机到它们）。riser(7) 本就不在池中。
    // 保留：回声/刹车/溶解/变速/连打/乐句/EQ。
    // 【V8.421】新增 T_EQ(10) 三段 EQ 交叉：经典 DJ EQ 过渡，低频在过渡中点交换、中高频等功率，
    // 与 threeBand 底层复用，听感即调音台 EQ 推子，是最常用的过渡之一。
    private val ALL = intArrayOf(T_ECHO, T_BRAKE, T_DISSOLVE, T_TEMPO, T_ROLL, T_PHRASE, T_EQ)

    /** 最近使用的模板（最近在前），用于多样性降权。 */
    private val recent = ArrayDeque<Int>(6)
    private const val RECENT_MAX = 4

    /** 单次决策的输入快照（避免反复读 prefs / cache）。 */
    data class Context(
        val aBpm: Int,           // 当前曲 BPM（已校正）
        val bBpm: Int,           // 候选曲 BPM（已校正）
        val aLufs: Float?,// 当前曲响度
        val bLufs: Float?,
        val aDurMs: Long,
        val bDurMs: Long,
        val xfadeMs: Int,
        val sameKey: Boolean,    // Camelot 同调性或相差 1
        val stemActive: Boolean  // 本轮是否走分轨总线（会旁路部分模板）
    ) {
        /** BPM 差百分比。 */
        val bpmDiffPct: Float
            get() = if (aBpm in 40..220 && bBpm in 40..220) {
                kotlin.math.abs(bBpm - aBpm).toFloat() / aBpm * 100f
            } else 0f

        /** 能量差（dB，带符号：正=新歌更响）。 */
        val lufsDiff: Float?
            get() = if (aLufs != null && bLufs != null) bLufs - aLufs else null
    }

    /**
     * 选出本轮模板。
     * @return 模板号；0 表示不启用（调用方按"关"处理）
     */
    fun pick(ctx: Context): Int {
        val candidates = filterCandidates(ctx).toMutableList()
        // 【Phase1】分轨可用时把 Neural Mix(声部交叉) 作为高优先级候选纳入自动选择
        if (ctx.stemActive && !candidates.contains(T_STEM)) candidates.add(T_STEM)
        if (candidates.isEmpty()) {
            // 全部被排除时退到"最保守"的那两个（回声/溶解不会把小节切碎，也不会出白噪）
            // 【V8.414】原为 (T_FILTER, T_DISSOLVE)，滤波已移除。
            val safe = intArrayOf(T_DISSOLVE, T_ECHO)
            val t = safe.random()
            record(t)
            Log.i(TAG, "pick tpl=$t (all filtered out -> safe fallback, bpmDiff=${"%.1f".format(ctx.bpmDiffPct)}%)")
            return t
        }

        val scored = candidates.map { t -> t to score(t, ctx) }
        val maxScore = scored.maxOf { it.second }
        // 同分（差值 < 1.0）视为并列，在并列里随机 —— 保留多样性但只从"好的"里选
        val top = scored.filter { maxScore - it.second < 1.0f }.map { it.first }
        val chosen = top.random()
        record(chosen)

        val reason = StringBuilder("pick tpl=$chosen from ${top.size} tied (cand=${candidates.size}")
        if (top.size > 1) reason.append(" of ").append(scored.size)
        reason.append(", bpmDiff=").append("%.1f".format(ctx.bpmDiffPct)).append("%")
        reason.append(", xf=").append(ctx.xfadeMs).append("ms")
        ctx.lufsDiff?.let { reason.append(", lufsDiff=").append("%.1f".format(it)) }
        if (ctx.sameKey) reason.append(", sameKey")
        if (ctx.stemActive) reason.append(", stem")
        reason.append(")")
        Log.i(TAG, reason.toString())
        return chosen
    }

    /** ① 硬性排除：明显不适配的组合直接不用。 */
    private fun filterCandidates(ctx: Context): IntArray {
        val diff = ctx.bpmDiffPct
        return ALL.filter { t ->
            when (t) {
                // Pitch Drop 是变调下扫：BPM 差大时音高会明显跑，磁带感=事故。
                // 只有节奏接近时才用它（此时 pitch 才听不出问题）。
                T_PITCH -> diff <= 2.0f

                // Tempo Blend 要靠变速对拍。BPM 差太大（>12%）要拉伸的量超出
                // Rubber Band 的自然范围，听感是"跑调"而不是"对拍"。
                T_TEMPO -> diff <= 12.0f

                // Beat Roll 切碎节奏：慢歌用它会把拍点切得稀碎，推进感全无。
                T_ROLL -> ctx.aBpm >= 100

                // 长 Echo 在快歌里会糊成一片，压掉新歌的进点。
                T_ECHO -> ctx.aBpm <= 132 || ctx.xfadeMs <= 9000

                // Phrase 按小节分段，需要有足够的小节数可分；过渡太短没法分段。
                T_PHRASE -> ctx.xfadeMs >= 4000

                // Brake 需要明确的"停顿→进入"感，太短的过渡体现不出来。
                T_BRAKE -> ctx.xfadeMs >= 3000

                else -> true
            }
        }.toIntArray()
    }

    /** ② 打分：越高越合适。 */
    private fun score(t: Int, ctx: Context): Float {
        val diff = ctx.bpmDiffPct
        var s = 5.0f

        // 与 BPM 差的契合度：不同模板的"最佳适配区间"不同
        s += when (t) {
            T_TEMPO -> 4.0f - kotlin.math.abs(diff - 6.0f) * 0.4f   // 差 6% 时最能体现"对拍"
            T_ROLL -> 2.6f - kotlin.math.abs(ctx.aBpm - 126f) / 26f  // 126 BPM 附近 roll 最顺
            // 【V8.401】Pitch Drop 降权。实测它是被选中最多的一个（20 刀里 5~8 次），
            // 而它的听感是"音高往下掉"——用户反馈的"总是刹车/下扫"其实就是它。
            // 全自动电台应该以**平滑无痕**为主，这类有明确形变的效果当点缀就好。
            T_PITCH -> 1.6f - diff * 0.4f   // 必须节奏接近（已排除 >2%）
            T_ECHO -> 2.0f - kotlin.math.abs(ctx.aBpm - 110f) / 40f
            T_BRAKE -> if (diff > 8f) 1.5f else 0f     // 差大时用刹车藏错位
            // 【V8.401】平滑类提权：Filter/Dissolve 是最不会露痕迹的过渡，
            // 应该是 Automix 的默认气质。
            T_DISSOLVE -> 2.0f
            T_FILTER -> 2.2f
            T_EQ -> 2.2f        // 【V8.421】EQ 三段交叉同样是最平滑无痕的过渡之一
            T_PHRASE -> 0.8f
            T_STEM -> 4.2f        // 【Phase1】Neural Mix：分轨可用时高优先级
            else -> 0f
        }

        // 【Phase1·BPM→曲风偏置】低 BPM 用短平过渡防人声打架；高 BPM 拉长做 Bass 长混音。
        val bpm = ctx.aBpm
        s += when {
            bpm in 40..99 -> when (t) {
                T_DISSOLVE, T_ECHO -> 1.4f
                T_ROLL, T_PHRASE -> -1.2f
                T_TEMPO -> 0.4f
                T_STEM -> 0.8f
                else -> 0f
            }
            bpm >= 130 -> when (t) {
                T_TEMPO, T_ROLL, T_PHRASE, T_STEM -> 1.4f
                T_DISSOLVE, T_ECHO -> 0.3f
                else -> 0f
            }
            else -> 0.2f
        }

        // 【Phase1】分轨可用时 Neural Mix 再加权，确保作为高优先级子模板被选中
        if (ctx.stemActive && t == T_STEM) s += 2.0f

        // 能量差惩罚：新歌比旧歌响太多时，回声/滤波类会显得突兀
        val ld = ctx.lufsDiff
        if (ld != null) {
            val absLd = kotlin.math.abs(ld)
            when (t) {
                T_ECHO, T_FILTER, T_DISSOLVE -> if (absLd > 4.0f) s -= 1.5f
                T_TEMPO, T_PHRASE, T_BRAKE -> if (absLd > 6.0f) s -= 1.0f
            }
        }

        // 同调性：和声类效果（echo/phrase）更自然；跨调性时滤波/溶解更安全
        if (ctx.sameKey) {
            // 【V8.401】同调性时平滑类同样是最优解（不只是 echo/phrase）
            if (t == T_ECHO || t == T_PHRASE) s += 0.8f
            if (t == T_FILTER || t == T_DISSOLVE) s += 0.6f
        } else {
            if (t == T_ECHO) s -= 1.0f        // 跨调性的回声更容易显脏
            if (t == T_FILTER || t == T_DISSOLVE) s += 0.5f
        }

        // ③ 多样性：最近用过的降权，避免连续重复显得机械
        val idx = recent.indexOf(t)
        if (idx >= 0) s -= (recent.size - idx) * 0.9f

        return s
    }

    private fun record(t: Int) {
        recent.remove(t)
        recent.addFirst(t)
        while (recent.size > RECENT_MAX) recent.removeLast()
    }

    /** 设置变更 / 曲目切换时调用，避免跨 set 沿用旧的多样性约束。 */
    fun resetHistory() {
        recent.clear()
    }
}