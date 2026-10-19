package com.sdw.music.player.core.audio.playback

import com.sdw.music.player.Song

/**
 * 【V8.710】DJ 智能选曲·谐波/BPM/能量 picker（从 MusicService 提取的纯函数群）。
 * 原为 MusicService 的 11 个 private fun（约 215 行）——选曲逻辑与播放服务无状态耦合，
 * 提取为无状态 object：所有外部依赖（当前曲 BPM、变速钳位窗、最近选曲窗口）由调用方参数化注入。
 * MusicService 侧保留同签名 private fun 薄委托，行为完全不变。
 */
object DjHarmonicPicker {
/**
 * 【v8.166】Camelot 和声距离：0=同调（最稳） 1=兼容（同号换号 / 相邻号同字母）
 * 3=不搭。任一侧未知返回 0（不惩罚，避免把未扫描的歌全排掉）。
 * 主流 DJ 软件（rekordbox/Serato）的 Key Sync 就是按这张表做相邻选择。
 */
fun keyScore(a: String, b: String): Int {
    if (a.isBlank() || b.isBlank()) return 0
    if (a == b) return 0
    val na = a.dropLast(1).toIntOrNull() ?: return 3
    val nb = b.dropLast(1).toIntOrNull() ?: return 3
    if (na !in 1..12 || nb !in 1..12) return 3
    val la = a.last()
    val lb = b.last()
    if (na == nb) return 1                                  // 8A ↔ 8B：能量升档
    if (la == lb) {
        val d = ((na - nb) + 12) % 12                       // 含 12A ↔ 1A 环绕
        if (d == 1 || d == 11) return 1                     // 8A ↔ 9A / 7A
    }
    return 3
}

/**
 * 【V8.221】Camelot 轮和谐判定（"8A" 形字符串）。规则取通行三则：
 *  同号（能量顺推）/ ±1 同字母（最平滑）/ ±3 异字母（大小调相对）。
 *  解析失败一律返回 false（宁可保守也不假报和谐）。
 */
fun camelotCompatible(a: String, b: String): Boolean {
    val re = Regex("(\\d{1,2})\\s*([AB])")
    val ma = re.find(a.trim().uppercase()) ?: return false
    val mb = re.find(b.trim().uppercase()) ?: return false
    val n1 = ma.groupValues[1].toIntOrNull() ?: return false
    val n2 = mb.groupValues[1].toIntOrNull() ?: return false
    val l1 = ma.groupValues[2]
    val l2 = mb.groupValues[2]
    if (n1 !in 1..12 || n2 !in 1..12) return false
    if (n1 == n2) return true
    if (kotlin.math.abs(n1 - n2) == 1 && l1 == l2) return true
    if (kotlin.math.abs(n1 - n2) == 3 && l1 != l2) return true
    return false
}

/** 候选 idx 相对当前曲的 BPM 距离（百分比，含半/倍速校正后比较）；无数据返回 -1f。 */
fun bpmDeltaPct(curBpm: Float, idx: Int, songs: List<Song>, bpmOf: (String) -> Float): Float {
    val sp = songs[idx].let { it.filePath.ifEmpty { it.path } }
    if (sp.isBlank()) return -1f
    val b = bpmOf(sp)
    if (b !in 40f..220f) return -1f
    return kotlin.math.abs(b - curBpm) / curBpm * 100f
}

/**
 * 【V8.419】把 pool 按「相对当前曲的 BPM 距离」分档。
 * @return Triple(最优档号, 该档候选, 全部档位按近→远的候选表)
 * 档位口径与变速钳位共用 `crossfade_rubberband_max`，避免"选得进来却拉不动"。
 * 当前曲或候选无 BPM 数据时返回 (2, pool, listOf(pool)) = 完全不改变原行为。
 */
fun bpmTierOf(pool: List<Int>, songs: List<Song>, curBpm: Float, bpmOf: (String) -> Float, maxPct: Int): Triple<Int, List<Int>, List<List<Int>>> {
    if (curBpm !in 40f..220f) return Triple(2, pool, listOf(pool))
    // GATE-EXEMPT: 只借档位口径做候选分档，不进变速链路（见顶部 banner 第 5 条 V8.427 豁免）
    val cand: List<Pair<Int, Float>> =
        pool.map { it to bpmDeltaPct(curBpm, it, songs, bpmOf) }.filter { it.second >= 0f }
    // 无任何候选有 BPM 数据 → 不改变原行为
    if (cand.isEmpty()) return Triple(2, pool, listOf(pool))
    val t0 = maxPct                       // 钳位窗内：拉得动
    val t1 = maxPct * 2f                 // 窗外但值得一试
    val buckets = listOf(
        cand.filter { it.second <= t0 }.map { it.first },
        cand.filter { it.second > t0 && it.second <= t1 }.map { it.first },
        cand.filter { it.second > t1 }.map { it.first }
    ).filter { it.isNotEmpty() }
    val best = buckets.firstOrNull() ?: pool
    return Triple(buckets.indexOf(best), best, buckets)
}

/** 【V8.419】按 BPM 档位由近到远铺开（用于 recent 排除后的阶梯放宽）。 */
fun allTiersInOrder(best: Int, buckets: List<List<Int>>): List<Int> {
    if (buckets.isEmpty()) return emptyList()
    val ordered = (best until buckets.size).map { buckets[it] } +
        (0 until best).reversed().map { buckets[it] }
    return ordered.flatten().distinct()
}

/**
 * 【V8.253 修改】能量最接近的候选：取 |ΔLUFS| 最近的 **5 首**（原实现是阈值 min+0.6dB，
 * 会把候选收窄到个位数——库里有响度数据的歌本来就少，来回就那几首， diversity 崩塌）。
 * 保底 5 首宽度，随机挑选仍有变化空间。
 */
fun closestEnergyBand(withE: List<Pair<Int, Float>>): List<Int> =
    withE.sortedBy { kotlin.math.abs(it.second) }.take(5).map { it.first }
        .takeIf { it.isNotEmpty() } ?: withE.map { it.first }

/** 候选相对当前曲的**带符号**能量差（Integrated LUFS，正=更响）；任一侧无扫描数据返回 null。 */
fun energyDelta(idx: Int, songs: List<Song>, curLufs: Float?): Float? {
    if (curLufs == null) return null
    val sp = songs[idx].let { it.filePath.ifEmpty { it.path } }
    val l = if (sp.isNotBlank()) com.sdw.music.player.core.audio.dsp.LoudnessCache.get(sp)?.lufs else null
    return l?.let { it - curLufs }
}

/** 取歌曲 Camelot 调性（优先 BpmKeyCache，回退 Song.key）。 */
fun camelotKeyOf(idx: Int, songs: List<Song>): String {
    val sp = songs[idx].let { it.filePath.ifEmpty { it.path } }
    return if (sp.isNotBlank()) com.sdw.music.player.BpmKeyCache.get(sp)?.second?.takeIf { it.isNotBlank() }
        ?: songs[idx].key else songs[idx].key
}

/** 【V8.501】按能量接近度给候选打分（|ΔLUFS| 越小权重越高）；无能量数据的歌给基础权重，仍可被随机选中。 */
fun weightByEnergy(pool: List<Int>, withE: List<Pair<Int, Float>>): Map<Int, Float> {
    val eMap = withE.toMap()
    return pool.associateWith { idx ->
        val e = eMap[idx]
        if (e != null) 1f / (1f + kotlin.math.abs(e) * 0.4f) else 0.5f
    }
}

/** 【V8.501·V8.9xx 修复】按权重随机选一首：能量/调性越接近越易被选中，但每轮都不同（根治"顺序固定"）。
 *  旧实现：权重来自 weightByEnergy（1/(1+|ΔLUFS|*0.4)，全部挤在 [0.5,1.0]），最接近的那首
 *  权重几乎永远最大 → weightedRandom 每轮都落在同一首 → 用户报"随机播放每次顺序都一样"。
 *  新实现：给每个权重乘一个 [JIT_MIN,1.0] 的随机抖动，让"最接近"不再必中——
 *  谐波/调性/BPM 兼容仍是软偏好（高权重相对更易被选中，过渡不炸），但同档与跨档都会被打散，
 *  每轮"哪首接哪首"都不同，多轮也不会锁死成固定序列。 */
fun weightedRandom(weights: Map<Int, Float>): Int {
    if (weights.isEmpty()) return -1
    val JIT_MIN = 0.4f
    val jittered = weights.mapValues { (_, w) ->
        w * (JIT_MIN + java.lang.Math.random().toFloat() * (1f - JIT_MIN))
    }
    val total = jittered.values.sum()
    if (total <= 0f) return weights.keys.random()
    var r = (java.lang.Math.random() * total).toFloat()
    for ((k, w) in jittered) { r -= w; if (r <= 0f) return k }
    return jittered.keys.last()
}

/**
 * 【V8.241】调和声 + 能量感知的候选挑选（专业 Automix 选曲核心）。
 * 先按 Camelot 和声分档：同调(0) > 兼容(1) > 不兼容(3)，只在「最兼容那一档」里挑；
 * 同档内再按 Integrated LUFS 能量接近度选最接近的一首，并留一点窄带随机避免锁死单曲。
 * 当前曲没有调性/能量数据时优雅降级：全部归到同档 → 退化为能量(或纯)随机。
 * 【V8.246】energyStrategy：0=smooth 平滑（|ΔLUFS| 最近的窄带，默认）
 *            1=build  逐步爬升（只挑比当前曲响 ≥0.5dB 的，取最接近的 3 首）
 *            2=wind   收尾降温（只挑比当前曲轻 ≥0.5dB 的，取最接近的 3 首）
 *            策略方向上无候选时回退平滑模式，永不空手。
 * @return 池里选中的下标（相对 songs）；池空返回 -1。
 */
fun pickHarmonic(pool: List<Int>, songs: List<Song>, curKey: String, curLufs: Float?, energyStrategy: Int = 0,
        curBpm: Float, bpmOf: (String) -> Float, maxPct: Int, recentIdx: Set<Int>, recentFirst: Int?): Int {
    if (pool.isEmpty()) return -1
    // 【V8.419·djay Automix 化】BPM 邻近度作为**第一优先轴**，排在 Camelot 调性之上。
    // 起因（19:12 实测）：9 刀变速对拍里 7 刀撞 ±6% 钳位，残留 BPM 误差均值 29.7%、
    // 最大 80.7% —— 选曲不看 BPM 时，"对齐"这个能力基本空转。djay Automix 的核心
    // 正是"挑 BPM/调性兼容的下一首"，先挑近的、再谈变速拉。
    // 分档与变速钳位一致（读同一个 pref），保证"选得进来"和"拉得动"永远同口径：
    //   0 = 钳位窗内（拉得动）→ 1 = 窗外但值得一试 → 2 = 远（别选）
    val bpmTiered = bpmTierOf(pool, songs, curBpm, bpmOf, maxPct)
    val bpmTier = bpmTiered.first
    val bpmCand = bpmTiered.second
    val byTier = bpmCand.groupBy { keyScore(curKey, camelotKeyOf(it, songs)) }
    // 取最兼容的一档；没有 0 就用 1，没有 1 就用 3
    val tier = listOf(0, 1, 3).firstOrNull { byTier.containsKey(it) } ?: 3
    val tierCand = byTier[tier]!!
    // 同档内按能量差选；能量未知则保持候选集（不淘汰任何歌）
    val withE = tierCand.mapNotNull { idx ->
        energyDelta(idx, songs, curLufs)?.let { idx to it }
    }
    val pickFrom = if (withE.isNotEmpty()) {
        val closest = closestEnergyBand(withE)
        when (energyStrategy) {
            1 -> withE.filter { it.second >= 0.5f }.sortedBy { it.second }
                .take(3).map { it.first }.takeIf { it.isNotEmpty() } ?: closest
            2 -> withE.filter { it.second <= -0.5f }.sortedByDescending { it.second }
                .take(3).map { it.first }.takeIf { it.isNotEmpty() } ?: closest
            else -> closest
        }
    } else tierCand
    // [V8.343] Diversity fix: pickFrom is often a tiny set (e.g. the 5 closest-energy
    // songs of one Camelot tier). With unchanged curKey/curLufs that set is identical
    // every round, so random() keeps landing on the same song -- observed twice in a
    // row (index 655, both rounds curKey='8B' curLufs=-11.11).
    // [V8.416] WIDENING FIX: handing out "most recent first" only helps when there IS
    // something outside the recent set -- but pickFrom itself is often <= 5 entries and
    // recentPicked holds up to 8, so `notRecent` was empty every round and the code fell
    // straight back to the same few songs. A 3000-song library still looped "the same
    // tracks" (user report: "BPM随机为什么歌曲总是这几首").
    // Now: keep the tight high-quality set while it still contains a fresh song;
    // otherwise walk a widening ladder (more energy neighbours -> whole tier ->
    // adjacent tiers -> whole pool) and take the first level that has a fresh song.
    val mergedTiers = (byTier[0].orEmpty() + byTier[1].orEmpty() + byTier[3].orEmpty()).distinct()
    // 【V8.501】智能选曲随机化：同一当前曲不再永远接同一首。
    // 旧逻辑只在「最佳 Camelot 档的能量最近 5 首」里 random()，集合常坍缩成 1~2 首
    // → 每轮同序列（用户反馈"智能选曲每次顺序都一样"）。
    // 新逻辑：把「策略紧集 pickFrom + 最佳 Camelot 档 + 次佳档」并成候选池，
    // 按能量接近度加权随机 —— 接近的更易被选中，但同档内与跨档都打散，
    // 每轮"哪首接哪首"都不同，同时保留 djay 式和声连贯与能量曲线方向。
    // 候选池不足 2 首时逐级并入更宽档兜底，避免又坍缩成固定 1 首。
    val stratOk: (Int) -> Boolean = { idx ->
        if (energyStrategy == 0) true
        else {
            val e = withE.firstOrNull { it.first == idx }?.second
            if (e == null) true else if (energyStrategy == 1) e >= 0.5f else e <= -0.5f
        }
    }
    val nextTierKey = when (tier) { 0 -> 1; 1 -> 3; else -> 0 }
    val basePool = (pickFrom + byTier[tier].orEmpty() + byTier[nextTierKey].orEmpty())
        .filter { stratOk(it) }.distinct()
    val baseW = weightByEnergy(basePool, withE)
    val chosen = if (baseW.size >= 2) baseW
        else weightByEnergy(mergedTiers.filter { stratOk(it) }, withE)
            .takeIf { it.isNotEmpty() }
            ?: weightByEnergy(allTiersInOrder(bpmTiered.first, bpmTiered.third).filter { stratOk(it) }, withE)
                .takeIf { it.isNotEmpty() }
            ?: baseW
    val freshChosen = chosen.filterKeys { it !in recentIdx }
    // 【V9.0·BPM 去重修复】freshChosen 为空（候选集 ≤ 最近 8 首选曲、全部已近期命中）时，
    // 不再直接 weightedRandom(chosen) 可能原地重选上一首；先剔除"最近刚播的那一首"兜底，
    // 仅当剔除后为空（整 BPM 窗只剩刚播过的 1 首这种极端情形）才退回原集。
    val fallback = if (freshChosen.isNotEmpty()) freshChosen
        else chosen.filterKeys { it != recentFirst }.ifEmpty { chosen }
    return weightedRandom(fallback)
}

}
