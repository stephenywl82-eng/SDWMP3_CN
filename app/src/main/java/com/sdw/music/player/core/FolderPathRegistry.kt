package com.sdw.music.player

/**
 * 文件夹路径注册表。
 *
 * 为什么需要：Navigation 的字符串 route 参数不适合承载完整文件夹路径——
 * 深层目录（第四级/更深）累积出的完整路径经过 Uri.encode 后会变得非常长，
 * 且路径中可能含特殊字符，容易触发崩溃。
 *
 * 做法：导航时把完整路径存入本表，route 只传一个短 token（Int），
 * 目的地根据 token 取回完整路径。彻底避开长路径与编码问题。
 */
object FolderPathRegistry {
    private val map = LinkedHashMap<Int, String>()
    private var nextId = 0

    /** 存入路径，返回递增 token。超量时淘汰最旧的条目，避免无限增长。 */
    @Synchronized
    fun put(path: String): Int {
        val id = ++nextId
        map[id] = path
        if (map.size > 512) {
            val it = map.entries.iterator()
            var removed = 0
            while (it.hasNext() && removed < 256) {
                it.next()
                it.remove()
                removed++
            }
        }
        return id
    }

    fun get(token: Int): String? = map[token]
}
