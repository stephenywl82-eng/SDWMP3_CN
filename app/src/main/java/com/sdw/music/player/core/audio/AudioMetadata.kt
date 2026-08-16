package com.sdw.music.player.core.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log

/**
 * Lightweight audio metadata reader using MediaExtractor.
 * Does NOT decode — just reads container headers.
 */
object AudioMetadata {
    private const val TAG = "AudioMetadata"

    /**
     * Read the sample rate of an audio file.
     * @return sample rate in Hz, or null if not readable
     */
    fun getSampleRate(path: String): Int? {
        if (path.isBlank()) return null

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    Log.d(TAG, "getSampleRate: $path -> $sr Hz (mime=$mime)")
                    return sr
                }
            }
            Log.w(TAG, "getSampleRate: no audio track in $path")
            null
        } catch (e: Exception) {
            Log.e(TAG, "getSampleRate failed for $path: ${e.message}")
            null
        } finally {
            extractor.release()
        }
    }
}
