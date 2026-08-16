package com.sdw.music.player

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 【Steven 崩溃诊断】全局未捕获异常处理器
 * 把崩溃信息写入文件，下次打On App 可以读取
 */
class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val TAG = "CrashHandler"
    private var appContext: Context? = null  // 【Steven】缓存 Context，不再依赖 MusicService

    companion object {
        private const val CRASH_FILE_NAME = "music_pro_crash.log"
        private var instance: CrashHandler? = null

        fun install(context: Context) {
            val handler = CrashHandler()
            handler.appContext = context.applicationContext
            instance = handler
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Log.d("CrashHandler", "Global crash handler installed (context=${context.javaClass.simpleName})")
        }

        /**
         * 读取上次崩溃日志
         */
        fun readCrashLog(context: Context): String {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (!file.exists()) return ""
            val content = file.readText()
            // 读取后删除，避免重复显示
            file.delete()
            return content
        }
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        Log.e(TAG, "=== UNCAUGHT EXCEPTION ===", exception)
        
        try {
            val ctx = appContext
            if (ctx == null) {
                Log.e(TAG, "appContext is null, cannot save crash log")
                defaultHandler?.uncaughtException(thread, exception)
                return
            }
            val crashLog = buildCrashReport(thread, exception)
            val file = File(ctx.filesDir, CRASH_FILE_NAME)
            FileWriter(file).use { writer ->
                writer.write(crashLog)
            }
            Log.e(TAG, "Crash log saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }

        // 交给默认处理器（系统会弹出"App Stopped"对话框）
        defaultHandler?.uncaughtException(thread, exception)
    }

    private fun buildCrashReport(thread: Thread, exception: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("========== CRASH REPORT ==========")
        sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        sb.appendLine("Thread: ${thread.name}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        sb.appendLine("=== Exception ===")
        sb.appendLine("${exception.javaClass.name}: ${exception.message}")
        sb.appendLine()
        
        // 完整堆栈
        exception.stackTrace?.forEach { frame ->
            sb.appendLine("    at $frame")
        }
        
        // Cause chain
        var cause = exception.cause
        while (cause != null) {
            sb.appendLine()
            sb.appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
            cause.stackTrace?.forEach { frame ->
                sb.appendLine("    at $frame")
            }
            cause = cause.cause
        }
        
        sb.appendLine()
        sb.appendLine("==================================")
        
        return sb.toString()
    }
}

