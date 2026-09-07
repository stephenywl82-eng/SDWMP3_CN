package com.sdw.music.player.core

import com.sdw.music.player.Song

/**
 * 【v8.13】BPM 平滑排序 + 随机播放 BPM 匹配
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
}
