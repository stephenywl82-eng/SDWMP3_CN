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
import com.sdw.music.player.MusicService
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.sdw.music.player.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.sdw.music.player.widget.NEXT"
        const val ACTION_PREV = "com.sdw.music.player.widget.PREV"

        private const val COMPACT_HEIGHT_DP = 72

        // SharedPreferences keys (mirrors MusicService savePlaybackState)
        private const val PREFS_PLAYBACK = "playback_state"
        private const val KEY_SONG_TITLE = "last_song_title"
        private const val KEY_SONG_ARTIST = "last_song_artist"
        private const val KEY_ALBUM_ART_URI = "last_album_art_uri"
        private const val KEY_WAS_PLAYING = "was_playing"
        private const val WIDGET_PREFS = "widget_prefs"
        private const val KEY_TRANSPARENT_BG = "transparent_bg"

        internal fun isWidgetBgTransparent(context: Context): Boolean =
            context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRANSPARENT_BG, false)

        // ── Entry point ────────────────────────────────────────────

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, MusicWidgetProvider::class.java)
            )
            if (widgetIds.isEmpty()) return
            updateWidgets(context, appWidgetManager, widgetIds)
        }

        // ── Data resolution (MusicService → SharedPreferences fallback) ──

        internal data class WidgetSongData(
            val title: String,
            val artist: String,
            val albumArtUri: String,
            val id: Long = 0L
        )

        internal fun resolveSongData(context: Context): WidgetSongData? {
            val serviceSong = MusicService.currentSong
            if (serviceSong != null) {
                return WidgetSongData(
                    title = serviceSong.title,
                    artist = serviceSong.artist,
                    albumArtUri = serviceSong.albumArtUri,
                    id = serviceSong.id
                )
            }
            val prefs = context.getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
            val title = prefs.getString(KEY_SONG_TITLE, "") ?: ""
            val artist = prefs.getString(KEY_SONG_ARTIST, "") ?: ""
            val albumArtUri = prefs.getString(KEY_ALBUM_ART_URI, "") ?: ""
            if (title.isBlank()) return null
            return WidgetSongData(title, artist, albumArtUri)
        }

        internal fun resolveIsPlaying(context: Context): Boolean {
            val service = MusicService.instance
            if (service != null) {
                // [v7.113] 优先使用事件驱动的最新状态（避免Oboe JNI延迟）
                return try { MusicService.lastKnownPlayingState } catch (_: Exception) { false }
            }
            val prefs = context.getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_WAS_PLAYING, false)
        }

        // ── Art bitmap cache (avoid re-decode on every button click) ──
        private var cachedArtUri: String? = null
        private var cachedArtBmp: android.graphics.Bitmap? = null

        // Fast path: only returns cached bitmap or logo, never decodes (no I/O)
        internal fun getArtBitmapFast(context: Context, songData: WidgetSongData?, rounded: Boolean): android.graphics.Bitmap {
            val uri = songData?.albumArtUri
            if (uri.isNullOrBlank()) return loadDefaultLogo(context)
            synchronized(artCacheLock) {
                if (uri == cachedArtUri) {
                    return cachedArtBmp ?: loadDefaultLogo(context)
                }
            }
            return loadDefaultLogo(context)
        }

        // Async decode + cache, then callback on main thread
        internal fun triggerAsyncCoverLoad(context: Context, uri: String, onDone: () -> Unit) {
            if (uri == cachedArtUri) { onDone(); return }
            thread(name = "widget-art-decode") {
                val bmp = decodeArtWidget(context, uri)
                Handler(Looper.getMainLooper()).post {
                    if (bmp != null) {
                        synchronized(artCacheLock) {
                            cachedArtBmp?.recycle()
                            cachedArtUri = uri
                            cachedArtBmp = bmp
                        }
                    }
                    onDone()
                }
            }
        }

        internal fun getArtBitmap(context: Context, songData: WidgetSongData?, rounded: Boolean): android.graphics.Bitmap? {
            val uri = songData?.albumArtUri
            if (uri.isNullOrBlank()) return loadDefaultLogo(context)
            if (uri == cachedArtUri) {
                return cachedArtBmp ?: loadDefaultLogo(context)
            }
            val bmp = decodeArtWidget(context, uri)
            if (bmp != null) {
                clearArtCache()
                cachedArtUri = uri
                cachedArtBmp = bmp
                return cachedArtBmp
            }
            return loadDefaultLogo(context)
        }

        private var cachedDefaultBmp: android.graphics.Bitmap? = null

        internal fun loadDefaultLogo(context: Context): android.graphics.Bitmap {
            if (cachedDefaultBmp == null) {
                cachedDefaultBmp = decodeDefaultCover(context)
            }
            return cachedDefaultBmp!!
        }

        /** Dp→px, 8dp = 8f */
        internal fun dpToPx(context: Context, dp: Float): Int =
            (dp * context.resources.displayMetrics.density + 0.5f).toInt()

        private val artCacheLock = Any()
        @JvmStatic internal fun getCachedArtUri(): String? = cachedArtUri

        fun clearArtCache() {
            synchronized(artCacheLock) {
                cachedArtUri = null
                cachedArtBmp?.recycle()
                cachedArtBmp = null
            }
        }

        // ── Widget update ──────────────────────────────────────────

        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val songData = resolveSongData(context)
            val isPlaying = resolveIsPlaying(context)
            val density = context.resources.displayMetrics.density

            for (widgetId in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(widgetId)
                val heightDp = (options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) / density).toInt()

                val layoutRes = if (heightDp <= COMPACT_HEIGHT_DP)
                    R.layout.widget_music_player_4x1
                else R.layout.widget_music_player_4x2
                val views = RemoteViews(context.packageName, layoutRes)

                // Transparent background
                if (isWidgetBgTransparent(context)) {
                    views.setInt(R.id.widget_root, "setBackgroundColor", 0x00000000)
                    views.setInt(R.id.widget_cover, "setBackgroundColor", 0x00000000)
                }

                // Cover art — fast path: cached bitmap or logo (no I/O on main thread)
                val rawBmp = getArtBitmapFast(context, songData, rounded = false)
                views.setImageViewBitmap(
                    R.id.widget_cover,
                    roundBitmap(rawBmp, dpToPx(context, 8f).toFloat())
                )

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
                    broadcastPending(context, widgetId, 1, ACTION_PLAY_PAUSE))
                views.setOnClickPendingIntent(R.id.widget_btn_next,
                    broadcastPending(context, widgetId, 2, ACTION_NEXT))
                views.setOnClickPendingIntent(R.id.widget_btn_prev,
                    broadcastPending(context, widgetId, 3, ACTION_PREV))

                views.setOnClickPendingIntent(R.id.widget_root,
                    PendingIntent.getActivity(context, widgetId * 10,
                        Intent(context, MainActivity::class.java).apply {
                            putExtra("open_player", true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

                appWidgetManager.updateAppWidget(widgetId, views)
            }

            // Async decode if cache miss (avoid blocking onContentResolver I/O)
            if (songData != null && songData.albumArtUri.isNotBlank() && songData.albumArtUri != cachedArtUri) {
                val appCtx = context.applicationContext
                triggerAsyncCoverLoad(appCtx, songData.albumArtUri) {
                    updateAllWidgets(appCtx)
                    MusicWidgetProvider3x2.updateAllWidgets(appCtx)
                }
            }
        }

        internal fun decodeArtWidget(ctx: Context, uriStr: String?): android.graphics.Bitmap? {
            if (uriStr.isNullOrBlank()) return null
            try {
                val uri = android.net.Uri.parse(uriStr)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

                val targetSize = 200
                opts.inSampleSize = maxOf(
                    maxOf(opts.outWidth, opts.outHeight) / targetSize, 1
                )
                opts.inJustDecodeBounds = false
                opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565

                return ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: Exception) { return null }
        }

        // ── Public helpers for 3x2 provider reuse ──────────────────

        internal fun broadcastPending(
            context: Context, widgetId: Int, req: Int, action: String
        ): PendingIntent {
            return PendingIntent.getBroadcast(context, widgetId * 10 + req,
                Intent(context, MusicWidgetProvider::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        internal fun decodeDefaultCover(context: Context): android.graphics.Bitmap {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.default_cover_purple)
                ?: return android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.default_cover)
            val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, 512, 512)
            drawable.draw(canvas)
            return bmp
        }

        internal fun roundBitmap(source: android.graphics.Bitmap, radiusPx: Float): android.graphics.Bitmap {
            val output = android.graphics.Bitmap.createBitmap(source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(output)
            val path = android.graphics.Path().apply {
                addRoundRect(
                    android.graphics.RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                    radiusPx, radiusPx,
                    android.graphics.Path.Direction.CW
                )
            }
            canvas.clipPath(path)
            canvas.drawBitmap(source, 0f, 0f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
            return output
        }

        internal fun handleAction(context: Context, action: String?) {
            when (action) {
                ACTION_PLAY_PAUSE -> {
                    MusicService.instance?.let {
                        if (it.isPlaying()) it.pause() else it.resume()
                    }
                }
                ACTION_NEXT -> {
                    MusicService.instance?.playNext()
                }
                ACTION_PREV -> {
                    MusicService.instance?.playPrevious()
                }
            }
            updateAllWidgets(context)
            MusicWidgetProvider3x2.updateAllWidgets(context)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onReceive(context: Context, intent: Intent) {
        handleAction(context, intent.action)
        if (intent.action !in setOf(ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREV)) {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray
    ) = updateWidgets(context, appWidgetManager, appWidgetIds)

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, widgetId: Int, opts: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, widgetId, opts)
        updateWidgets(context, appWidgetManager, intArrayOf(widgetId))
    }
}