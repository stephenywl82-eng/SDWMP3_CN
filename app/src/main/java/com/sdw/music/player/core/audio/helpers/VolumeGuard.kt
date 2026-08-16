package com.sdw.music.player.core.audio.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 【v4.79】全局音量监听：静音自动暂停 + 恢复音量自动继续
 * 从 MusicService 拆出，MusicService 只需提供 pause/resume 回调
 */
class VolumeGuard(
    private val context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val isPlaying: () -> Boolean,
    private val onVolumeChanged: ((Float) -> Unit)? = null  // [V8.x] USB DAC volume callback
) {
    private val TAG = "VolumeGuard"
    private val handler = Handler(Looper.getMainLooper())

    var wasPlayingBeforeMute = false
        private set
    var isMuted = false
        private set

    // [v7.113] 防抖：避免快速调音量时竞态条件
    private var lastVolumeCheckTime = 0L
    private val VOLUME_DEBOUNCE_MS = 300L

    private val mutedRecheck = object : Runnable {
        override fun run() {
            if (isMuted) {
                checkVolumeAndAutoPause()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            checkVolumeAndAutoPause()
        }
    }

    fun register() {
        try {
            context.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
            Log.d(TAG, "Volume receiver registered (global)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register volume receiver: ${e.message}")
        }
    }

    fun unregister() {
        try { context.unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
    }

    fun resetMuteState() {
        isMuted = false
        wasPlayingBeforeMute = false
        handler.removeCallbacks(mutedRecheck)
    }

    private fun checkVolumeAndAutoPause() {
        // [v7.113] 防抖
        val now = System.currentTimeMillis()
        if (now - lastVolumeCheckTime < VOLUME_DEBOUNCE_MS) return
        lastVolumeCheckTime = now

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val isCurrentlyMuted = currentVolume == 0
        val playing = isPlaying()
        
        Log.d(TAG, "checkVolume: vol=$currentVolume muted=$isCurrentlyMuted playing=$playing isMuted=$isMuted")

        if (isCurrentlyMuted && !isMuted) {
            isMuted = true
            handler.removeCallbacks(mutedRecheck)
            handler.postDelayed(mutedRecheck, 1000)
            if (playing) {
                wasPlayingBeforeMute = true
                onPause()
                Log.d(TAG, "Volume muted → auto pause (global)")
            }
        } else if (!isCurrentlyMuted && isMuted) {
            isMuted = false
            handler.removeCallbacks(mutedRecheck)
            val shouldResume = wasPlayingBeforeMute
            wasPlayingBeforeMute = false
            // [v7.113] 延迟恢复，确保 Oboe/ExoPlayer 状态稳定
            if (shouldResume) {
                handler.postDelayed({
                    onResume()
                    Log.d(TAG, "Volume restored → auto resume (global)")
                }, 200)
            }
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 1
            onVolumeChanged?.invoke(currentVolume.toFloat() / maxVol)
        } else if (isCurrentlyMuted) {
            // [V8.x] Already muted — still notify for completeness
            onVolumeChanged?.invoke(0f)
        } else {
            isMuted = false
            wasPlayingBeforeMute = false
            handler.removeCallbacks(mutedRecheck)
            // 【V3.3.17】正常音量调节（非静音）也通知 USB DAC
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 1
            onVolumeChanged?.invoke(currentVolume.toFloat() / maxVol)
        }
    }
}
