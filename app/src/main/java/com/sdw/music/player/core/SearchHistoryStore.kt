package com.sdw.music.player.core

import android.content.Context

/**
 * [2026-10-25] 搜索历史存储（用户要求：主界面搜索框点击后有搜索记录）。
 *
 * - SharedPreferences 持久化，进程重启不丢
 * - 最近使用的在前，自动去重（重复搜索把旧条提到最前）
 * - 上限 [MAX] 条，超出丢最旧的
 * - 查询词可能含任意字符，用 \u0001（控制符，正常输入不可能出现）做分隔
 */
object SearchHistoryStore {

    private const val PREFS = "search_history"
    private const val KEY_QUERIES = "queries"
    private const val SEP = "\u0001"
    const val MAX = 12

    /** 读取历史（最近使用的在前）。 */
    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUERIES, null) ?: return emptyList()
        return raw.split(SEP).filter { it.isNotBlank() }.take(MAX)
    }

    /** 记一次搜索：去重提到最前，超上限丢尾。空白词忽略。 */
    fun add(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val current = load(context).toMutableList()
        current.removeAll { it.equals(q, ignoreCase = true) }
        current.add(0, q)
        save(context, current.take(MAX))
    }

    /** 删单条。 */
    fun remove(context: Context, query: String) {
        val current = load(context).toMutableList()
        if (!current.remove(query)) return
        save(context, current)
    }

    /** 清空。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_QUERIES).apply()
    }

    private fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUERIES, list.joinToString(SEP)).apply()
    }
}
