package com.sdw.music.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.sdw.music.player.MainActivity
import com.sdw.music.player.R

/**
 * 3x2 widget provider — always uses the 3x2 layout.
 * Data resolution and action handling are fully delegated to MusicWidgetProvider.
 */
class MusicWidgetProvider3x2 : AppWidgetProvider() {

    companion object {
        private val LAYOUT_3X2 = R.layout.widget_music_player_3x2

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, MusicWidgetProvider3x2::class.java))
            if (widgetIds.isEmpty()) return

            val songData = MusicWidgetProvider.resolveSongData(context)
            val isPlaying = MusicWidgetProvider.resolveIsPlaying(context)

            for (widgetId in widgetIds) {
                val views = RemoteViews(context.packageName, LAYOUT_3X2)

                // Transparent background
                if (MusicWidgetProvider.isWidgetBgTransparent(context)) {
                    views.setInt(R.id.widget_root, "setBackgroundColor", 0x00000000)
                    views.setInt(R.id.widget_cover, "setBackgroundColor", 0x00000000)
                }

                // Cover art — fast path: cached bitmap or logo (no I/O on main thread)
                val rawBmp = MusicWidgetProvider.getArtBitmapFast(context, songData)
                val coverBmp = MusicWidgetProvider.roundBitmap(
                    rawBmp, MusicWidgetProvider.dpToPx(context, 8f).toFloat())
                views.setImageViewBitmap(R.id.widget_cover, coverBmp)

                // Text
                if (songData != null) {
                    views.setTextViewText(R.id.widget_title, songData.title)
                    views.setTextViewText(R.id.widget_artist, songData.artist)
                } else {
                    views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name_moto))
                    views.setTextViewText(R.id.widget_artist, "No track playing")
                }

                // Controls
                views.setImageViewResource(R.id.widget_btn_play_pause,
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

                views.setOnClickPendingIntent(R.id.widget_btn_play_pause,
                    MusicWidgetProvider.broadcastPending(context, widgetId, 1,
                        MusicWidgetProvider.ACTION_PLAY_PAUSE))
                views.setOnClickPendingIntent(R.id.widget_btn_next,
                    MusicWidgetProvider.broadcastPending(context, widgetId, 2,
                        MusicWidgetProvider.ACTION_NEXT))
                views.setOnClickPendingIntent(R.id.widget_btn_prev,
                    MusicWidgetProvider.broadcastPending(context, widgetId, 3,
                        MusicWidgetProvider.ACTION_PREV))

                views.setOnClickPendingIntent(R.id.widget_root,
                    PendingIntent.getActivity(context, widgetId * 10,
                        Intent(context, MainActivity::class.java).apply {
                            putExtra("open_player", true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE))

                appWidgetManager.updateAppWidget(widgetId, views)
            }

            // Async decode if cache miss
            if (songData != null && songData.albumArtUri.isNotBlank() && songData.albumArtUri != MusicWidgetProvider.getCachedArtUri()) {
                val appCtx = context.applicationContext
                MusicWidgetProvider.triggerAsyncCoverLoad(appCtx, songData.albumArtUri) {
                    MusicWidgetProvider.updateAllWidgets(appCtx)
                    updateAllWidgets(appCtx)
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        MusicWidgetProvider.handleAction(context, intent.action)
        if (intent.action !in setOf(MusicWidgetProvider.ACTION_PLAY_PAUSE,
                MusicWidgetProvider.ACTION_NEXT, MusicWidgetProvider.ACTION_PREV)) {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray
    ) = updateAllWidgets(context)

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, widgetId: Int, opts: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, widgetId, opts)
        updateAllWidgets(context)
    }
}