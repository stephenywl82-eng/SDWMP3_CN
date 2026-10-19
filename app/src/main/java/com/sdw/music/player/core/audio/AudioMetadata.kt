package com.sdw.music.player.core.audio
import com.sdw.music.player.core.audio.analysis.MediaProbe

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

        // 【V8.429】收敛到单线程闸门：MediaExtractor 内部 codec 的事件线程还在派发时
        // 就release 会触发 framework 断言 abort（NDK MediaCodec_ / SIGABRT）。详见 MediaProbe。
        return MediaProbe.onProbeThread {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        Log.d(TAG, "getSampleRate: $path -> $sr Hz (mime=$mime)")
                        return@onProbeThread sr
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
}
