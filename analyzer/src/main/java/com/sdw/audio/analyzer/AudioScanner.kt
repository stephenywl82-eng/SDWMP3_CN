package com.sdw.audio.analyzer

import android.content.ContentResolver
import android.provider.MediaStore

/**
 * Scans local audio files via MediaStore and returns list of [AudioFileInfo].
 *
 * Filters: IS_MUSIC=1, duration >= minDurationMs (default 10s).
 * Path blacklist: Recordings, Call, MicroMsg, Notifications, Ringtones, Alarms, Recorder.
 */
class AudioScanner(private val contentResolver: ContentResolver) {

    /** Minimum audio duration in milliseconds. */
    var minDurationMs: Long = 10_000

    private val pathBlacklist = arrayOf(
        "/Recordings/", "/Voice Recorder/", "/CallRecordings/", "/Call/",
        "/MicroMsg/", "/Notifications/", "/Ringtones/", "/Alarms/",
        "/Recorder/", "/record/", "/Music/Recorder/", "/Sound_recorder/",
        "/Telephony/"
    )

    fun scan(): List<AudioFileInfo> {
        val result = mutableListOf<AudioFileInfo>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selArgs = arrayOf(minDurationMs.toString())

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selArgs,
            MediaStore.Audio.Media.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue

                // Apply path blacklist
                if (pathBlacklist.any { path.contains(it, ignoreCase = true) }) continue
                // Apply filename blacklist
                if (name.contains("Ringtone", ignoreCase = true) ||
                    name.contains("来电", ignoreCase = true) ||
                    name.contains("去电", ignoreCase = true)) continue

                result.add(AudioFileInfo(
                    mediaStoreId = cursor.getLong(idCol),
                    filePath = path,
                    fileName = name,
                    mimeType = cursor.getString(mimeCol) ?: "",
                    durationMs = cursor.getLong(durCol),
                    fileSize = cursor.getLong(sizeCol),
                    artist = cursor.getString(artistCol) ?: "",
                    album = cursor.getString(albumCol) ?: "",
                    title = cursor.getString(titleCol) ?: name
                ))
            }
        }
        return result
    }
}

data class AudioFileInfo(
    val mediaStoreId: Long,
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val durationMs: Long,
    val fileSize: Long,
    val artist: String,
    val album: String,
    val title: String
) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
    val isLossless: Boolean get() = extension in setOf("flac", "wav", "aiff", "alac", "ape", "wv")
    val isHighRes: Boolean get() = false // determined during analysis from actual sample rate
}
