package com.sdw.music.player.core.audio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object DebugLog {
    // [V3.3.6] 性能优化：使用线程安全的并发队列
    private val buffer = ConcurrentLinkedQueue<String>()
    private const val maxLines = 200
    
    // 使用 ThreadLocal 避免多线程竞争 SimpleDateFormat
    private val formatter = ThreadLocal.withInitial { 
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US) 
    }
    
    // [V3.3.6] 性能开关：禁用面板显示以消除 UI 卡顿
    @Volatile var enabled = false

    fun add(tag: String, msg: String) {
        if (!enabled) return  // 禁用时直接跳过，不阻塞主线程
        
        val timestamp = formatter.get()!!.format(Date())
        val line = "$timestamp $tag  $msg"
        
        // 线程安全添加，超过容量时移除最旧的
        buffer.offer(line)
        while (buffer.size > maxLines) {
            buffer.poll()
        }
        
        android.util.Log.d("SDW_$tag", msg)
    }

    /** Verbose: logcat only, kept OUT of the on-screen panel buffer */
    fun v(tag: String, msg: String) {
        android.util.Log.d("SDW_$tag", msg)
    }

    fun addWithLogCat(tag: String, msg: String) {
        add(tag, msg)
        android.util.Log.d("SDW_$tag", msg)
    }

    fun get(): String = buffer.joinToString("\n")

    fun clear() { 
        buffer.clear()
    }
}
