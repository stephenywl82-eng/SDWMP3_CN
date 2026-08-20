package com.sdw.music.player

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,           // content URI（用于播放）
    val albumArtUri: String,
    val format: String = "",    // 音频格式：MP3, FLAC, WAV 等
    val filePath: String = "",  // 实际文件路径（用于Folders分组）
    val folderPath: String = "", // 【v1.7】父Folders路径（从 filePath 提取）
    val bpm: Int = 0,          // 【Steven】节拍速度 (BPM)
    val key: String = "",     // 【Steven】调性 (Key, e.g. Am, Cm)
    val genre: String = "",    // 【Steven】音乐流派
    val dateAdded: Long = 0L,  // 【Steven v1.6】MediaStore 添加时间（秒级时间戳），用于智能排序
    val isFavorite: Boolean = false,  // 【v1.8】Favorites状态
    val lastPlayedAt: Long = 0L  // 上次播放时间（秒级时间戳），用于"最近"过滤
) : Parcelable


/**
 * 拆分多歌手字符串。
 * 常见分隔："/"、"、"、"，"、","、";"、"；"、"&"、"·"、"|"
 * 以及 feat./ft./featuring/vs.（忽略大小写）。
 * 无分隔时返回原串（单元素）。
 */
fun splitArtists(artist: String): List<String> {
    if (artist.isBlank()) return listOf(artist)
    val regex = Regex(
        """\s*(?:[/、,;；&·|]|feat\.?|ft\.?|featuring|vs\.?)\s*""",
        RegexOption.IGNORE_CASE
    )
    return artist.split(regex)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(artist) }
}


