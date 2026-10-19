package com.sdw.music.player.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 构建打开 Lyrico 单曲编辑页的 Intent。
 *
 * Lyrico 的外部编辑契约（官方 external-edit 指南）要求：
 *  - action = "com.lonx.lyrico.action.EDIT_TAG"
 *  - 目标包名 = "com.lonx.lyrico"（调试版为 com.lonx.lyrico.debug）
 *  - 数据必须是 content:// URI（MIME 类型为 audio），且授予读+写权限
 *  - 纯 file:// 路径会被拒绝，必须用 FileProvider 包装成 content://
 *
 * 返回 null 的情况：
 *  - filePath 为空或文件不存在
 *  - 文件不在主存储授权范围内（如可移动 SD 卡），FileProvider 抛 IllegalArgumentException
 * 调用方应在返回 null 时提示“该文件不支持 Lyrico 编辑”。
 * 是否安装 Lyrico 由调用方用 resolveActivity 判断。
 */
fun buildLyricoEditIntent(context: Context, filePath: String): Intent? {
    val uri: Uri? = if (filePath.startsWith("content://")) {
        Uri.parse(filePath)
    } else {
        val file = File(filePath)
        if (!file.exists()) return null
        try {
            FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        } catch (e: IllegalArgumentException) {
            null // 不在主存储授权范围内（如 SD 卡）
        }
    }
    if (uri == null) return null
    return Intent("com.lonx.lyrico.action.EDIT_TAG").apply {
        setPackage("com.lonx.lyrico")
        setDataAndType(uri, "audio/*")
        clipData = ClipData.newRawUri("audio", uri)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
}

/**
 * Lyrico 在外部编辑回传 result Intent 的 extras 中携带的标签键。
 * 与 Lyrico 端 `ExternalEditExtras` 对齐（见 Lyrico PR #196）。
 */
object LyricoResultExtras {
    const val TITLE = "com.lonx.lyrico.extra.TITLE"
    const val ARTIST = "com.lonx.lyrico.extra.ARTIST"
    const val ALBUM_ARTIST = "com.lonx.lyrico.extra.ALBUM_ARTIST"
    const val ALBUM = "com.lonx.lyrico.extra.ALBUM"
    const val GENRE = "com.lonx.lyrico.extra.GENRE"
    const val LYRICS = "com.lonx.lyrico.extra.LYRICS"
}

/**
 * result Intent 的 extras 是否携带 Lyrico 编辑后的标签。
 * 有则就地更新内存中的歌曲，无（老版 Lyrico 未回传）则走整库重扫兜底。
 */
fun hasLyricoEditExtras(extras: android.os.Bundle?): Boolean =
    extras != null && (extras.containsKey(LyricoResultExtras.TITLE)
        || extras.containsKey(LyricoResultExtras.ARTIST)
        || extras.containsKey(LyricoResultExtras.ALBUM)
        || extras.containsKey(LyricoResultExtras.GENRE))
