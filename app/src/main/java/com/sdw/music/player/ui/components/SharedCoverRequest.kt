package com.sdw.music.player.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

/**
 * V8.56 One album cover, one decode, one memory entry.
 *
 * The mini player and the full player used to build their own ImageRequest for the very
 * same artwork, each with its own decode size:
 *   - mini player: 40dp canvas      -> ~112px
 *   - full player: up to 240dp art  -> ~675px
 * Coil keys its memory cache by (data + size + ...), so those two requests could never
 * share an entry: the same picture was decoded twice and held twice in RAM. The visible
 * costs were the shared-element flight decoding on the fly (the stutter) and the blur
 * fixed in V8.54.
 *
 * Both call sites now go through here, so the request - and therefore the cache key - is
 * byte-for-byte identical. The first one to need it decodes; the second is a cache hit.
 *
 * Decode size is the flight's LARGEST frame (the full player's art), so the element only
 * ever scales down as it flies. 260dp covers every layout branch (portrait 240, foldable
 * 260, landscape, Razr outer 170/190).
 */
object SharedCoverRequest {

    /** Stable key prefix; the URI is appended so different covers never collide. */
    private const val CACHE_KEY_PREFIX = "sdw_cover_shared:"

    /**
     * V8.66 Two tiers instead of one.
     *
     * V8.56 made every caller share ONE request so the cover was decoded once. That killed
     * the duplicate decode, but it used the FLIGHT's size as the common denominator - and
     * the flight's largest frame is the full player's art, 260dp. On this panel (450dpi =
     * 2.8125x) that is 731x731 = 2.1MB per cover, and every one of the ~390 list rows was
     * decoding that to draw itself inside a 48dp (135px) circle: 30x the pixels it can
     * even show. 390 x 2.1MB = ~840MB of bitmap churn through the LRU while scrolling,
     * which is exactly the list-scroll and flight stutter we measured (90th pct 42ms,
     * 95th 61ms, 99th 117ms on a 60Hz panel).
     *
     *   BIG   - mini player + full player + the flight. Must stay sharp, and the flight
     *           is the largest consumer, so this keeps 260dp.
     *   SMALL - list rows / sheets / pickers. The tallest list cover is 48dp; at 450dpi
     *           that draws at 135px, so 160px leaves headroom for larger rows and for the
     *           odd tablet density without ever going soft.
     *
     * The two tiers intentionally have DIFFERENT cache keys: they are different bitmaps.
     * The one that matters - mini player <-> full player - still shares a single entry,
     * which is the pair the shared-element flight actually uses.
     */
    const val BIG_DP = 260f
    const val SMALL_PX = 160

    /** Stable key prefix; the URI is appended so different covers never collide. */
    fun cacheKey(uri: String): String = CACHE_KEY_PREFIX + uri

    /** Cache key for the big (flight / player) tier. */
    fun cacheKeyBig(uri: String): String = CACHE_KEY_PREFIX + "big:" + uri

    /**
     * Decode size in px for the flight tier: the full player's art size derived from
     * screen density, so no magic number has to be tuned per device.
     */
    fun decodePx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return (BIG_DP * density).toInt().coerceAtLeast(240)
    }
}

/**
 * Cover painter backed by the shared request above.
 *
 * V8.66: [big] picks the tier.
 *   - big = true  -> flight tier (260dp). Mini player + full player. These two MUST share
 *                    a cache key because they are the two ends of the shared-element
 *                    flight; the flight samples this one bitmap all the way across.
 *   - big = false -> list tier (96px). Rows, sheets, pickers. Small enough that scrolling
 *                    a few hundred rows stays cheap, large enough that the 48dp row circle
 *                    is still pixel-exact at 450dpi.
 *
 * Defaulting to false is deliberate: the overwhelming majority of call sites are list
 * rows, and a row that accidentally gets the big bitmap is the exact regression this
 * split exists to prevent.
 */
@Composable
fun rememberSharedCoverPainter(
    uri: String?,
    contentScale: ContentScale = ContentScale.Crop,
    big: Boolean = false
): AsyncImagePainter {
    val context = LocalContext.current
    val bigPx = remember(context) { SharedCoverRequest.decodePx(context) }
    val model = remember(uri, big, bigPx) {
        uri?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(if (big) bigPx else SharedCoverRequest.SMALL_PX)
                .memoryCacheKey(
                    if (big) SharedCoverRequest.cacheKeyBig(it)
                    else SharedCoverRequest.cacheKey(it)
                )
                .allowHardware(false)
                .crossfade(false)
                .build()
        }
    }
    return rememberAsyncImagePainter(model = model, contentScale = contentScale)
}
