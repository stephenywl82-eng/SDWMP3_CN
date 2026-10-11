package com.sdw.music.player.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V8.110 - the light/dark flip used to be a hard cut: `setLightMode` flips a MutableState,
 * the colorScheme object is swapped, and every screen repaints in the new palette on the
 * very next frame. No interpolation, no overlap - the eye reads it as a flash rather than
 * as a change of light.
 *
 * Three layers add the missing transition. All of them are pure UI: no audio path, no
 * playback state, no navigation is touched, and while idle the cost is zero.
 *
 *  L1 - full-screen liquid diffusion (【ThemeFlipOverlay】). A soft-edged disc of the OLD
 *       background colour expands from the point that was actually tapped (the light-mode
 *       switch), then dissolves. The new palette is already in place underneath, so the
 *       result is a cross-dissolve with a radial leading edge instead of a wipe.
 *  L2 - the wavefront reads as "the glass re-frosting": a bright soft band rides the
 *       expanding edge, and the app's own glass panes (see Glass.kt) temporarily raise
 *       their fill opacity so surfaces look like they re-freeze as the light passes over
 *       them. Exposed here as 【ThemeFlip.reveal】.
 *  L3 - staggered reveal of the settings rows, driven by 【ThemeFlip.active】; the per-index
 *       stagger itself lives in SettingsScreen because it needs the item indices.
 *
 * Why this is not Modifier.blur: minSdk here is 24 and a real backdrop blur is a
 * RenderEffect (API 31+), so it would silently do nothing on a large share of devices -
 * the same trade-off Glass.kt already documents. The diffusion is plain gradients, which
 * read identically on every API level.
 *
 * Why the flip is detected rather than pushed: 【ThemeFlipOverlay】 sits inside
 * MaterialTheme, so the frame AFTER a change already carries the new background. Watching
 * `colorScheme.background` change is therefore the earliest reliable signal that the new
 * palette is on screen, and it covers every entry point (the switch, a wallpaper change
 * that flips the effective mode, and any future caller) without hand-wiring each one. The
 * switch only has to call 【ThemeFlip.mark】 beforehand to hand over the tap point.
 */
object ThemeFlip {

    private val _origin = mutableStateOf<Offset?>(null)
    private val _fromBackground = mutableStateOf(Color.Black)
    private val _active = mutableStateOf(false)
    private val _reveal = mutableStateOf(0f)
    private val _token = mutableStateOf(0)

    /** Screen-space (px) point the flip started from, or null to fall back to centre. */
    val origin: State<Offset?> = _origin

    /** The background colour in force before the flip (the colour that diffuses out). */
    val fromBackground: State<Color> = _fromBackground

    /** True while the diffusion is running; drives L3's staggered reveal. */
    val active: State<Boolean> = _active

    /** 0..1 "frost" amount, bell-shaped over the flip; consumed by `glassSurface`. */
    val reveal: State<Float> = _reveal

    /** Bumped on every flip; keys the diffusion animation so it restarts cleanly. */
    val token: State<Int> = _token

    /**
     * Record the tap that caused the flip. Call immediately BEFORE changing the theme;
     * 【origin】 is in root/screen pixels.
     */
    fun mark(origin: Offset?) {
        _origin.value = origin
        _token.value = _token.value + 1
    }

    /** Internal: the overlay spotted the palette change. */
    fun trigger(previousBackground: Color) {
        _fromBackground.value = previousBackground
        // Bump the token here too: the diffusion animation is keyed on it, and the flip may
        // have been started without mark() (e.g. a wallpaper change that flipped the
        // effective mode), in which case this is the only signal to restart it.
        _token.value = _token.value + 1
        _active.value = true
    }

    /** Internal: called each frame by the overlay. */
    fun setReveal(value: Float) {
        _reveal.value = value
    }

    /** Internal: the diffusion finished. */
    fun finished() {
        _reveal.value = 0f
        _active.value = false
    }
}

/**
 * L1 + L2 renderer. Mounted once, at the very top of 【SDWMusicTheme】's content, so it
 * covers every screen including the status/nav bar areas (the app is edge-to-edge).
 * Composes nothing while idle - not even a Canvas.
 */
@Composable
fun ThemeFlipOverlay() {
    // The palette change is detected here: this composable is inside MaterialTheme, so on
    // the frame after a flip `background` is already the NEW value while `lastBackground`
    // still holds the old one.
    val background = MaterialTheme.colorScheme.background
    var lastBackground by remember { mutableStateOf(background) }
    LaunchedEffect(background) {
        if (background != lastBackground) {
            ThemeFlip.trigger(lastBackground)
            lastBackground = background
        }
    }

    val active by ThemeFlip.active
    if (!active) return

    val token by ThemeFlip.token
    val originPx = ThemeFlip.origin.value
    val fromBg = ThemeFlip.fromBackground.value
    // The new palette is already live underneath (MaterialTheme recomposed this frame).
    val accent = MaterialTheme.colorScheme.primary

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val metrics = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        val w = with(density) { configuration.screenWidthDp.dp.toPx() }
        val h = with(density) { configuration.screenHeightDp.dp.toPx() }
        Triple(w, h, sqrt(w * w + h * h).coerceAtLeast(1f))
    }
    val (width, height, maxRadius) = metrics
    val focus = originPx ?: Offset(width / 2f, height / 2f)

    val progress = remember(token) { Animatable(0f) }

    LaunchedEffect(token) {
        val runner = launch {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing)
            )
        }
        // Drive L2's frost amount from the same clock without recomposing per frame.
        snapshotFlow { progress.value }.collect { p ->
            ThemeFlip.setReveal(frostBell(p))
        }
        runner.join()
        ThemeFlip.finished()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val p = progress.value
        val radius = (p * maxRadius).coerceAtLeast(0.01f)
        // Hold the old palette for the first 60% of the travel, then dissolve it away so
        // the new palette is what is left. Never fully opaque on purpose: the blend against
        // the already-swapped palette is what makes it read as a dissolve of the light
        // rather than a hard wipe.
        val tail = if (p < 0.6f) 1f else (1f - (p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (tail <= 0f) return@Canvas

        // L1 - the diffusing disc of the OLD background colour.
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to fromBg.copy(alpha = 0.62f * tail),
                0.86f to fromBg.copy(alpha = 0.62f * tail),
                1.00f to Color.Transparent,
                center = focus,
                radius = radius
            ),
            radius = radius,
            center = focus
        )
        // L2 - the re-frosting band riding the leading edge.
        drawCircle(
            brush = Brush.radialGradient(
                0.76f to Color.Transparent,
                0.90f to Color.White.copy(alpha = 0.30f * tail),
                1.00f to Color.Transparent,
                center = focus,
                radius = radius
            ),
            radius = radius,
            center = focus
        )
        // A hint of the destination accent on the outermost lip, so the new theme is
        // foreshadowed rather than simply appearing.
        drawCircle(
            brush = Brush.radialGradient(
                0.94f to Color.Transparent,
                0.98f to accent.copy(alpha = 0.22f * tail),
                1.00f to Color.Transparent,
                center = focus,
                radius = radius
            ),
            radius = radius,
            center = focus
        )
    }
}

/**
 * L2's clock shape: 0 at the endpoints, peaking mid-travel - exactly when the wavefront is
 * sweeping across the screen. pow(0.8) widens the plateau slightly so the frosted feel
 * lingers instead of being a single-frame spike.
 */
private fun frostBell(p: Float): Float {
    if (p <= 0f || p >= 1f) return 0f
    return sin(p * Math.PI).toFloat().pow(0.8f)
}

/**
 * L3 - one settings row, fading in slightly after the one above it. Keyed on
 * 【ThemeFlip.active】 so the stagger only runs as part of a flip; when idle (and on first
 * composition) the row is simply fully opaque.
 */
@Composable
fun ThemeStagger(index: Int, content: @Composable () -> Unit) {
    val active by ThemeFlip.active
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            alpha.snapTo(0f)
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 380,
                    delayMillis = 40 + index * 8,
                    easing = FastOutSlowInEasing
                )
            )
        } else {
            alpha.snapTo(1f)
        }
    }
    Box(modifier = Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}
