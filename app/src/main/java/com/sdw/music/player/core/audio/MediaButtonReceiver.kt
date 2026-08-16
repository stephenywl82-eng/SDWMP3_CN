package com.sdw.music.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")
        
        val serviceIntent = Intent(context, MusicService::class.java).apply {
            action = intent.action
        }
        
        context.startService(serviceIntent)
    }
}
