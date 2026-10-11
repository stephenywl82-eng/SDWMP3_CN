package com.sdw.music.player.core

import com.sdw.music.player.BpmKeyCache
import com.sdw.music.player.Song
import kotlin.math.abs

/**
 * 【v8.13】BPM 平滑排序 + 随机播放 BPM 匹配
 * 【V8.244】能量/调性感知的「流程排序」（Rekordbox Auto-Flow 思路）
 */
object SongSorter {

    /**
     * BPM 平滑排序（贪心最近邻）
     * 从中位数 BPM 的歌开始，每次挑 BPM 最接近当前尾歌的未选歌。
     * 形成节奏渐变链（120→124→128→132…），比纯升序 DJ 感强。
     * 无 BPM（0）的歌排到最后（保持相对顺序）。
     */
    fun bpmSmoothSort(songs: List<Song>): List<Song> {
        if (songs.size <= 2) return songs
        val withBpm = songs.filter { it.bpm > 0 }
        val withoutBpm = songs.filter { it.bpm <= 0 }
        if (withBpm.isEmpty()) return songs

        // 从中位数开始
        val sorted = withBpm.sortedBy { it.bpm }
        val mid = sorted[sorted.size / 2]

        val remaining = withBpm.toMutableList()
        remaining.remove(mid)
        val result = mutableListOf(mid)

        while (remaining.isNotEmpty()) {
            val tail = result.last().bpm
            var bestIdx = 0
            var bestDiff = Int.MAX_VALUE
            for (i in remaining.indices) {
                val diff = kotlin.math.abs(remaining[i].bpm - tail)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = i
                }
            }
            result.add(remaining.removeAt(bestIdx))
        }
        return result + withoutBpm
    }

    /**
     * 随机播放 BPM 匹配：从候选池里优先挑 BPM 与当前歌接近的。
     * @param pool 随机池（已排除当前歌）
     * @param currentBpm 当前歌 BPM（0 = 未知）
     * @param tolerance BPM 容差（默认 ±5）
     * @return 候选池中 BPM 匹配的子集；池空回退全池
     */
    fun bpmMatchPool(pool: List<Song>, currentBpm: Int, tolerance: Int = 5): List<Song> {
        if (currentBpm <= 0) return pool
        val matched = pool.filter { it.bpm in (currentBpm - tolerance)..(currentBpm + tolerance) }
        return if (matched.isNotEmpty()) matched else pool
    }

    /**
     * 能量/调性感知的「流程排序」（Rekordbox Auto-Flow 思路）。
     * 贪心最近邻：从能量最低的歌起步，每步挑「能量差最小 + 调性最兼容」的下一首，
     * 形成一条能量平滑爬升、相邻调和的播放链；无调性/BPM 数据的歌排到末尾。
     *
     * 复杂度 O(n^2)，歌单量级（数百首）完全够用。
     */
    fun flowSort(songs: List<Song>): List<Song> {
        if (songs.size <= 2) return songs
        val keyOf: (Song) -> String = { s ->
            val path = s.filePath.ifEmpty { s.path }
            (if (path.isNotBlank()) BpmKeyCache.get(path)?.second?.takeIf { it.isNotBlank() } else null) ?: s.key
        }
        data class Node(val song: Song, val e: Int, val k: String)
        val nodes = songs.map { Node(it, EnergyColor.energyOf(it), keyOf(it)) }
        val usable = nodes.filter { it.k.isNotBlank() || it.song.bpm > 0 }
        val rest = nodes.filter { it !in usable }.map { it.song }
        if (usable.isEmpty()) return songs

        val remaining = usable.toMutableList()
        // 起步：能量最低（最柔的歌开场，整条链自然往上推）
        val start = remaining.minByOrNull { it.e } ?: remaining.first()
        remaining.remove(start)
        val result = mutableListOf(start)
        while (remaining.isNotEmpty()) {
            val tail = result.last()
            var best: Node? = null
            var bestCost = Float.MAX_VALUE
            for (n in remaining) {
                val dE = abs(n.e - tail.e).toFloat()
                val pk = keyPenalty(tail.k, n.k)
                val cost = 0.6f * dE + 0.8f * pk
                if (cost < bestCost) { bestCost = cost; best = n }
            }
            result.add(best!!)
            remaining.remove(best)
        }
        return result.map { it.song } + rest
    }

    /** 调性惩罚：同调 0 / 兼容(±1 同字母 或 ±3 异字母) 0.5 / 不兼容 1.5；无数据 1.0。 */
    private fun keyPenalty(a: String, b: String): Float {
        val na = camelotNum(a); val nb = camelotNum(b)
        val la = camelotLetter(a); val lb = camelotLetter(b)
        if (na == null || nb == null || la == null || lb == null) return 1.0f
        if (na == nb) return 0.0f
        if (la == lb) {
            val d = ((na - nb) + 12) % 12
            if (d == 1 || d == 11) return 0.5f
        }
        if (la != lb) {
            val d = abs(na - nb)
            if (d == 3 || d == 9) return 0.5f
        }
        return 1.5f
    }

    private fun camelotNum(key: String): Int? {
        val m = Regex("(\\d{1,2})\\s*([AB])").find(key.trim().uppercase()) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return if (n in 1..12) n else null
    }

    private fun camelotLetter(key: String): Char? {
        val m = Regex("(\\d{1,2})\\s*([AB])").find(key.trim().uppercase()) ?: return null
        return m.groupValues[2].firstOrNull()
    }
}
