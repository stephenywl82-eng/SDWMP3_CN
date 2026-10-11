package com.sdw.music.player.ui.animation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * V8.44 Shared M3-expressive motion specs.
 *
 * Compose 1.6.1 predates Material 3 MotionScheme, so these are the hand-rolled
 * equivalents. Springs are used wherever a *spatial* property moves (position,
 * scale, size) because they react to interruption and never look mechanical.
 * Opacity / colour keeps a time-based tween so fades stay linear to the eye.
 */
object Motion {

    /** Standard spatial motion: gentle settle, slight overshoot. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Expressive entrance: more bounce, for things appearing in front of the user. */
    fun <T> expressive(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Snappy: pressed states, small controls. */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun offsetSpatial(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Spatial size change (expand/shrink vertically or horizontally). V8.58 */
    fun sizeSpatial(): FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Spatial size change with a snappier settle, for small inline reveals. V8.58 */
    fun sizeSnappy(): FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Cover flight (mini player -> full player). V8.65.
     *
     * Was spring(0.78, StiffnessMediumLow), which settles in about 250ms. The user's
     * words: "it flies away before I can feel it" - and they are right, a full-screen
     * morph at 250ms reads as a cut, not as an object moving.
     *
     * V8.65 first went to stiffness 100 (about 440ms) and that overshot the other way -
     * V8.66 feedback: "the fly-out is too slow now". Settling time scales as 1/sqrt(k),
     * so stiffness 190 lands at roughly 320ms: long enough to read as travel, short
     * enough to still feel like the tap caused it. dampingRatio 0.9 keeps the small
     * overshoot that makes it land with weight instead of drifting to a stop.
     */
    fun coverFlight(): FiniteAnimationSpec<androidx.compose.ui.geometry.Rect> = spring(
        dampingRatio = 0.9f,
        stiffness = 190f
    )

    /** Navigation push/pop. Interruptible, but settles in roughly the old 420ms tween
     *  so the hand-off feels immediate rather than sluggish. V8.58 */
    fun navSpatial(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = 0.88f,
        stiffness = Spring.StiffnessMedium
    )

    // ---- Opacity / colour still use time ----
    val fadeInFast: FiniteAnimationSpec<Float> = tween(180, easing = FastOutSlowInEasing)
    val fadeInNormal: FiniteAnimationSpec<Float> = tween(260, easing = FastOutSlowInEasing)
    val fadeOutFast: FiniteAnimationSpec<Float> = tween(140, easing = FastOutSlowInEasing)

    /** Accent / colour transitions. */
    val colorStandard: FiniteAnimationSpec<androidx.compose.ui.graphics.Color> =
        tween(600, easing = FastOutSlowInEasing)

    /** Emphasized decelerate, for large surface movements. */
    val emphasized: CubicBezierEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    // ---- V8.60 fade-through navigation -------------------------------------
    // A spring settles in ~300ms and, worse, it was cross-faded against the outgoing
    // page: for a stretch both pages sat half-transparent at different offsets, and the
    // outgoing page's edge read as a hard vertical seam sliding across the screen. The
    // fix is the M3 "fade through" pattern - the outgoing page is gone before the
    // incoming one appears, so only ever one page is visible. Times are deliberately
    // longer than the old 420/360ms: the user found the push too quick to follow.
    val navSlideIn: FiniteAnimationSpec<IntOffset> = tween(640, easing = emphasized)
    val navSlideOut: FiniteAnimationSpec<IntOffset> = tween(520, easing = emphasized)

    // [V8.97] Predictive back (pop direction only) uses springs instead of the tweens above.
    //
    // A tween is the wrong spec for a gesture-driven pop. When the finger is released the
    // tween restarts the REMAINING distance on a fixed 640ms clock, so a half-way swipe
    // still drifts for over half a second after the hand has stopped - the motion reads as
    // detached from the finger, which is exactly the "跟手" this version is trying to add.
    // A spring starts from wherever the SeekableTransitionState seek left off and carries
    // the release velocity, so the page arrives as fast as the hand was moving; it also
    // stays interruptible, so a second swipe can grab it back mid-flight.
    //
    // dampingRatio NoBouncy (1f): a full-screen page slide must not overshoot - critical
    // damping gives a clean landing with no edge jiggle. Stiffness is deliberately low
    // (tuned to settle around 350-400ms, i.e. the same range as the push the user
    // approved) because V8.60 already recorded "the push was too quick to follow".
    // Push (enter/exit) keeps the tween - it is tap-driven, so there is no velocity to
    // carry and the approved timing still applies.
    val navPopSlideIn: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 120f
    )
    val navPopSlideOut: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 160f
    )
    // Fade-through still holds: outgoing is gone at 180ms, incoming only starts at
    // 200ms, so the two pages are never simultaneously readable (that overlap is what
    // produced the hard vertical seam back in V8.60). Longer, but the gap is preserved.
    val navFadeIn: FiniteAnimationSpec<Float> = tween(320, delayMillis = 200, easing = emphasized)
    val navFadeOut: FiniteAnimationSpec<Float> = tween(180, easing = FastOutSlowInEasing)

    /**
     * Player page fade. V8.65.
     *
     * The player page cross-fades against the flying cover, so this has to OUTLAST the
     * flight (coverFlight is now ~440ms). If the page finishes appearing first it reads
     * as "already arrived" while the art is still travelling - which is precisely the
     * "no animation, it just flew away" feeling. Deliberately longer than the flight.
     */
    val navPlayerFadeIn: FiniteAnimationSpec<Float> = tween(500, delayMillis = 60, easing = emphasized)
    val navPlayerFadeOut: FiniteAnimationSpec<Float> = tween(280, easing = FastOutSlowInEasing)
    val navPlayerPopFadeOut: FiniteAnimationSpec<Float> = tween(260, easing = FastOutSlowInEasing)

    /**
     * V8.84 Player pop. The page used to slide straight up off the top edge, which read
     * as "the page left" rather than "the page went back". Shrinking it instead reveals the
     * song list around its edges, so the two screens are visibly related. Slight bouncy so
     * it lands with weight; the fade rides along on top of it.
     */
    val playerPopScale: FiniteAnimationSpec<Float> = spring(
        dampingRatio = 0.88f,
        stiffness = Spring.StiffnessMediumLow
    )
}
