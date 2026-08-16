package com.sdw.music.player

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 文件日志：把关键链路写进 app 内部文件，供 adb run-as 拉取诊断。
 * 独立写线程 + 无锁队列，不阻塞主线程。
 */
object FileLog {
    private val queue = ConcurrentLinkedQueue<String>()
    private var file: File? = null
    private var writer: Thread? = null
    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        try {
            file = File(context.filesDir, "debug_trace.log")
            if (file!!.exists() && file!!.length() > 2_000_000) {
                file!!.delete()
            }
            writer = Thread({
                var bw: BufferedWriter? = null
                try {
                    bw = BufferedWriter(FileWriter(file!!, true), 8192)
                    while (true) {
                        val line = queue.poll()
                        if (line == null) {
                            bw.flush()
                            Thread.sleep(20)
                            continue
                        }
                        bw.write(line)
                        bw.write("\n")
                        // 每 50 行 flush 一次，平衡性能与丢日志风险
                        if (queue.size % 50 == 0) bw.flush()
                    }
                } catch (_: InterruptedException) {
                    // ignore
                } catch (_: Exception) {
                    // ignore
                } finally {
                    try { bw?.flush(); bw?.close() } catch (_: Exception) {}
                }
            }, "filelog-writer")
            writer!!.isDaemon = true
            writer!!.start()
        } catch (_: Exception) {}
    }

    fun log(tag: String, msg: String) {
        if (!started) return
        if (queue.size > 20000) return  // 防爆内存
        val t = System.currentTimeMillis()
        queue.offer("$t [$tag] $msg")
    }

    // ---- 帧率采样：记录每帧时间戳，用于区分动画/重组/轮询节奏 ----
    fun startFrameTrace() {
        if (!started) return
        try {
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val t = System.currentTimeMillis()
                    queue.offer("$t [FRAME] $frameTimeNanos")
                    if (queue.size < 20000) {
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
            Choreographer.getInstance().postFrameCallback(cb)
        } catch (_: Exception) {}
    }
}
