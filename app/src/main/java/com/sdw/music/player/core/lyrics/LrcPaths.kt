package com.sdw.music.player.lyric

import android.util.Log
import java.io.File

/**
 * 【V8.95】.lrc 路径推导 + 写入安全闸门（单一事实来源）
 *
 * == 为什么需要这个类 ==
 *
 * 旧代码用「白名单正则」推导歌词路径：
 *
 *     audioPath.replace(Regex("\\.(flac|mp3|m4a|wav|ogg)$"), ".lrc")
 *
 * 这个写法在扩展名**不在白名单**时会灾难性失效：
 *   - 正则不命中 → String.replace 返回原字符串 → lrcPath == audioPath
 *   - 调用方拿这个路径去写文件 → 直接把**音频源文件**截断成歌词文本
 *   - 不可逆：整首歌的音频数据当场丢失
 *
 * 实测事故：.opus 不在白名单，13 个文件（周杰倫/Unholy/Covert…）被覆盖成
 * 1~3 KB 的歌词，之后播放报 "Failed to open file / length=1290" 永久卡住。
 *
 * 危险缺口不止 opus：aac / ape / dsf / dff / aiff / mka / amr / wv / tta /
 * spc / mpc 以及未来任何新扩展名，走 LyricUtils 那条白名单路径全都会中招。
 * 所以「补全白名单」是治标 —— 下次加个新格式又会复发。
 *
 * == 做法 ==
 * 1. derive 与扩展名**无关**：切掉最后一个扩展名再接 .lrc，任何格式都对。
 * 2. safeTarget 是**硬闸门**：只要推导结果等于音频文件本身，一律拒绝写入并报错。
 *    宁可歌词不落盘，也绝不碰音频半个字节。
 */
object LrcPaths {

    private const val TAG = "LrcPaths"

    /**
     * 去掉最后一个扩展名（保留目录与其余部分）。
     * @return 无扩展名、空路径、以 '.' 结尾时返回 null
     */
    fun deriveBase(audioPath: String): String? {
        if (audioPath.isBlank()) return null
        if (audioPath.startsWith("content://")) return null
        val slash = audioPath.lastIndexOf('/')
        val dot = audioPath.lastIndexOf('.')
        if (dot <= slash) return null                    // 没有扩展名（或点出现在目录名里）
        if (dot == audioPath.length - 1) return null     // 以 '.' 结尾
        return audioPath.substring(0, dot)
    }

    /** 与扩展名无关地推导同名 .lrc 路径；推导不出返回 null。 */
    fun derive(audioPath: String): String? = deriveBase(audioPath)?.plus(".lrc")

    /** 去掉纯文件名的扩展名（用于 SAF 显示名）。 */
    fun stripExtension(fileName: String): String {
        val s = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = s.lastIndexOf('.')
        return if (dot > 0) s.substring(0, dot) else s
    }

    /**
     * 安全闸门：返回可以放心写入的 .lrc 文件。
     *
     * 凡是以下情况一律返回 null 并记录错误，调用方必须直接放弃写入：
     *   - 推导不出路径（空路径 / content:// / 无扩展名）
     *   - 推导结果与音频源文件相同（**这是灾难性覆盖的唯一入口**）
     */
    fun safeTarget(audioPath: String): File? {
        val lrcPath = derive(audioPath)
        if (lrcPath == null) {
            Log.w(TAG, "拒绝推导 lrc 路径（无效音频路径）: $audioPath")
            return null
        }
        if (lrcPath.equals(audioPath, ignoreCase = true)) {
            Log.e(TAG, "⛔ 拒绝写入：lrc 路径与音频源文件相同，已阻止覆盖: $audioPath")
            return null
        }
        return File(lrcPath)
    }
}
