package com.sdw.music.player.core.audio.helpers

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player

class VisualizerManager(
    private val getPlayer: () -> Player?,
    private val getFftCallback: () -> ((ByteArray) -> Unit)?,
    private val tag: String = "VisualizerManager"
) {
    private var visualizer: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var givenUp = false // once Oboe mode fails, stop retrying until process restart

    fun setup() {
        if (givenUp) return // Oboe mode: no audio session, don't bother
        release()
        // Get audioSessionId from underlying ExoPlayer (falls back to 0 in Oboe mode)
        // [Phase C] ExoPlayer 已删除，无 AudioTrack session；Visualizer 不再可用（Oboe 走 native FFT）
        val audioSessionId = 0
        if (audioSessionId == 0) {
            if (retryCount < 3) {
                retryCount++
                Log.d(tag, "Audio session ID not ready, retry $retryCount/3 in 800ms")
                handler.postDelayed({ setup() }, 800)
            } else {
                Log.w(tag, "Audio session ID never ready after $retryCount retries, giving up permanently")
                givenUp = true
                retryCount = 0
            }
            return
        }
        givenUp = false
        retryCount = 0 // success, reset

        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        fft?.let { getFftCallback()?.invoke(it) }
                    }
                }, 15000, false, true)
                enabled = true
            }
            Log.d(tag, "Visualizer initialized with session ID: $audioSessionId")
        } catch (e: Exception) {
            Log.w(tag, "Visualizer construction failed (${e.message})")
            visualizer = null
            if (retryCount < 3) {
                retryCount++
                handler.postDelayed({ setup() }, 800)
            } else {
                Log.e(tag, "Visualizer failed after $retryCount retries, giving up permanently")
                givenUp = true
                retryCount = 0
            }
        }
    }

    fun release() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        Log.d(tag, "Visualizer released")
    }

    fun isReady(): Boolean = visualizer != null && visualizer?.enabled == true

    fun retry() {
        if (givenUp) return
        if (getFftCallback() != null && visualizer == null) {
            setup()
        }
    }
}
