@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.sdw.music.player.ui.animation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext

/**
 * V8.52 Official shared-element plumbing.
 *
 * We used to hand-roll the mini-player -> full-player cover flight in
 * [SharedCoverOverlay] (manual Rect interpolation + a separate overlay drawn on top of
 * the NavHost). Compose 1.7 ships the real thing: SharedTransitionLayout + sharedElement,
 * which animates the element inside the navigation transition itself, so it stops
 * following the finger only because it never had to fake it.
 *
 * Rather than thread two extra parameters through every screen and layout signature, the
 * two scopes are published through composition locals and picked up where the cover is
 * actually drawn. Applying the modifier is then a single chained call.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Spatial curve for the cover flight. Slight overshoot + interruptible, which is what
 * makes the morph feel like an object rather than a crossfade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val CoverBoundsTransform = BoundsTransform { _, _ -> Motion.coverFlight() }

/**
 * Marks this element as the flying cover. Both the mini-player cover and the full-player
 * cover chain this with the SAME key, which is what pairs them up.
 *
 * Safe by construction: if either scope is missing (e.g. a screen rendered outside the
 * shared transition host) the modifier is a no-op and the cover just draws normally.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCoverArt(
    key: String = SharedCoverKeys.COVER,
    clipShape: Shape? = null
): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalNavVisibilityScope.current ?: return this
    // [V8.74] The overlay clip is applied at EVERY frame of the flight, using the
    // interpolated bounds. A fixed CircleShape therefore forced a circular silhouette all
    // the way and then popped into the destination's shape on the last frame - which is
    // exactly the "abrupt" landing in immersive mode, where the cover is a rounded square.
    // A PERCENT-based rounded rect resolves through CornerSize.toPx() = minDimension *
    // percent/100, so the radius scales with the animated size. [V8.75] percent lowered
    // 9 -> 6 because 22dp on a ~239dp cover read as a generic rounded card rather than a
    // premium full-bleed square. Now: ~2.4dp leaving the 40dp mini player (its own rounding
    // is 4dp, the gap is sub-pixel at that scale) and ~14dp on arrival at the ~239dp player
    // cover, which itself uses percent = 6 so both ends agree. Continuous, no pop.
    // Non-immersive keeps CircleShape untouched (zero regression).
    val effectiveClip: Shape = clipShape ?: run {
        val immersive = LocalContext.current
            .getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("immersive_player", false)
        if (immersive) RoundedCornerShape(percent = 6) else CircleShape
    }
    val contentState = sharedScope.rememberSharedContentState(key = key)
    // [V8.53] The flying cover is drawn in a shared-transition overlay, and by default
    // Compose clips it to the PARENT layout bounds (OverlayClip.ParentClip). Our parent is
    // the square Box(sizeDp) that wraps the art, so the flight showed a big square that
    // popped into a circle at the end - and the same square appeared on every in-place
    // cover swap. Clipping the overlay to the art's own shape fixes both.
    val overlayClip = sharedScope.OverlayClip(effectiveClip)
    return with(sharedScope) {
        this@sharedCoverArt.sharedElement(
            state = contentState,
            animatedVisibilityScope = visibilityScope,
            boundsTransform = CoverBoundsTransform,
            clipInOverlayDuringTransition = overlayClip
        )
    }
}

/**
 * Publishes both scopes to a destination's subtree. One call per nav destination that
 * takes part in the cover flight, so no screen signature needs a new parameter.
 */
@Composable
fun SharedElementDestination(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    dragProgress: Float = 0f,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalSharedTransitionScope provides sharedScope,
        LocalNavVisibilityScope provides visibilityScope,
        LocalCoverDragProgress provides dragProgress,
        content = content
    )
}

/** Stable keys so the two ends can never drift apart. */
object SharedCoverKeys {
    const val COVER = "sdw.cover"
}

/**
 * V8.52 Live predictive-back drag progress (0..1), published by the player destination.
 *
 * The retired hand-rolled overlay used to translate the cover with the finger during a
 * back gesture. The official shared element only animates once the transition starts, so
 * the finger-follow is reproduced here by offsetting the art while the gesture is live.
 */
val LocalCoverDragProgress = compositionLocalOf { 0f }
