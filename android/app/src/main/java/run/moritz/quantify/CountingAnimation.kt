package run.moritz.quantify

import android.graphics.BitmapShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import run.moritz.quantify.counting.Heatmap
import run.moritz.quantify.counting.Point

// While counting, the photo shimmers like Google Photos analysing it: it wobbles slightly, except
// for the exemplar, and a soft ring of light spreads from the exemplar across the crop every
// SCAN_PERIOD, lighting up the contours it passes. The crop dims over DIM_FADE.
private const val SCAN_PERIOD = 1.8f
private const val DIM_ALPHA = 0.3f
private const val DIM_FADE = 0.3f
// When the count arrives, one fast wave reveals the heatmap and pops the points up as it reaches
// them; then the glow condenses onto its peaks and fades.
private const val REVEAL = 0.9f
private const val CONDENSE = 0.6f
private const val POP = 0.3f
// Clearing plays the reveal backwards: from the farthest point in to the exemplar, the points
// shrink away one after another at once.
private const val HIDE = 0.3f
private const val SHRINK = 0.2f
// How far behind the wave front, as a fraction of the whole way, a place is fully revealed.
private const val REVEAL_SOFTNESS = 0.15f
// The glow starts this opaque, and sharpens from this power of the heatmap to that as it
// condenses.
private const val GLOW_ALPHA = 0.65f
private const val GAMMA_FROM = 1.5f
private const val GAMMA_TO = 10f
// How far a popping point overshoots its size: the common choice, by 10%.
private const val OVERSHOOT = 1.70158f

/**
 * Scans the photo while it is counted, reveals the count along a [Wave] from the exemplar when it
 * arrives, and hides its points again when it is cleared: what to draw of it at each frame.
 */
@Stable
class CountingAnimation {
    // Seconds since counting started, since its result arrived and since it was cleared; null
    // while that part does not run.
    private var scanning by mutableStateOf<Float?>(null)
    private var revealing by mutableStateOf<Float?>(null)
    private var hiding by mutableStateOf<Float?>(null)

    /** The count whose points are hiding, until they are gone. */
    internal var cleared by mutableStateOf<CountState?>(null)
        private set

    /** The arrival of the cleared count's farthest point, where hiding begins. */
    private var farthest by mutableFloatStateOf(1f)

    private var wave by mutableStateOf<Wave?>(null)
    private var arrivals: FloatArray? = null
    private var arrivalShader: BitmapShader? = null
    // Filled anew every frame of the reveal.
    private var dim = IntArray(0)
    private var glow = IntArray(0)
    // Created on first use, only where the device can run shaders.
    private var shimmer: ShimmerShader? = null
    private var distortion: DistortionShader? = null

    /**
     * Hides the points of [count], which is about to be cleared; called right before clearing, so
     * no frame shows them gone before they hide.
     */
    fun hide(count: CountState) {
        val points = count.counted ?: return
        val wave = wave ?: return
        farthest = points.maxOfOrNull(wave::arrival)?.coerceAtLeast(1e-6f) ?: return
        cleared = count
        hiding = 0f
    }

    /** What to show of [state]: a count still hiding, unless the photo has changed since. */
    fun shown(state: CountState): CountState = cleared?.takeIf { it.photo === state.photo } ?: state

    /**
     * How large to draw a counted point at [point] in image pixels while the count is revealed: 0
     * until the wave reaches it, then popping up past 1 and settling at 1; while it is cleared,
     * shrinking back to 0 as the wave returns to the exemplar.
     */
    fun pointScale(point: Point): Float {
        if (scanning != null) return 0f
        hiding?.let { seconds ->
            val wave = wave ?: return 0f
            val hit = (1 - wave.arrival(point) / farthest).coerceIn(0f, 1f) * HIDE
            val left = 1 - ((seconds - hit) / SHRINK).coerceIn(0f, 1f)
            // Shrinking fastest at first, so it visibly starts at once.
            return left * left
        }
        val seconds = revealing ?: return 1f
        val wave = wave ?: return 1f
        val pop = (seconds - reached(wave.arrival(point))) / POP
        return if (pop <= 0) 0f else easeOutBack(pop.coerceAtMost(1f))
    }

    /**
     * Draws the scan's dimming or the reveal inside [crop], all in view coordinates: the [heatmap]
     * placed at [heatmapRect] glowing in [glowColor] where the wave has passed.
     */
    fun DrawScope.drawCountingAnimation(
        crop: Rect,
        heatmap: Heatmap?,
        heatmapRect: Rect?,
        glowColor: Color,
    ) {
        clipRect(crop.left, crop.top, crop.right, crop.bottom) {
            scanning?.let { seconds ->
                drawRect(
                    Color.Black.copy(alpha = DIM_ALPHA * fadeIn(seconds)),
                    crop.topLeft,
                    crop.size,
                )
            }
            val seconds = revealing
            if (seconds != null && heatmap != null && heatmapRect != null) {
                revealPixels(heatmap, seconds, glowColor)
                val columns = heatmap.columns
                val rows = heatmap.rows
                drawRect(cellsBrush(dim, columns, rows, heatmapRect), crop.topLeft, crop.size)
                drawRect(
                    cellsBrush(glow, columns, rows, heatmapRect),
                    crop.topLeft,
                    crop.size,
                    blendMode = BlendMode.Screen,
                )
            }
        }
    }

    /**
     * The render effect on the photo, in view coordinates: the scan's shimmer around [exemplar], or
     * the reveal's front bending it; null while neither runs or the device cannot run shaders.
     */
    internal fun distortion(
        exemplar: Rect,
        crop: Rect,
        heatmapRect: Rect?,
        density: Density,
    ): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        scanning?.let { seconds ->
            val shimmer = shimmer ?: ShimmerShader().also { shimmer = it }
            return shimmer.effect(
                exemplar,
                crop,
                reach(exemplar, crop),
                seconds,
                SCAN_PERIOD,
                fadeIn(seconds),
                density,
            )
        }
        val distortion = distortion ?: DistortionShader().also { distortion = it }
        val revealing = revealing
        if (revealing == null || revealing >= REVEAL) return null
        val reach = reach(exemplar, crop)
        val arrival = arrivalShader(heatmapRect) ?: return null
        return distortion.effect(
            exemplar,
            crop,
            front(revealing) * reach,
            fadeLate(revealing / REVEAL),
            arrival,
            reach,
            density,
        )
    }

    /** Reveals along [wave] from now on: the wave of the count shown. */
    internal fun useWave(wave: Wave?) {
        if (wave === this.wave) return
        this.wave = wave
        arrivals = wave?.arrivals()
        arrivalShader = null
    }

    /** Scans until cancelled, calling [onSweep] as each sweep sets off. */
    internal suspend fun scan(onSweep: () -> Unit) {
        var sweeps = 0
        everyFrame { seconds ->
            scanning = seconds
            if (seconds >= sweeps * SCAN_PERIOD) {
                sweeps++
                onSweep()
            }
        }
    }

    /** Stops scanning, then reveals the count if [reveal], calling [onReveal] as it sets off. */
    internal suspend fun stopScanning(reveal: Boolean, onReveal: () -> Unit) {
        scanning = null
        if (reveal) {
            onReveal()
            everyFrame(until = REVEAL + CONDENSE) { seconds -> revealing = seconds }
        }
        revealing = null
    }

    /** Hides the points of [count], cleared by [hide], until they are gone or cancelled. */
    internal suspend fun hideCleared(count: CountState) {
        try {
            everyFrame(until = HIDE + SHRINK) { seconds -> hiding = seconds }
        } finally {
            if (cleared === count) {
                hiding = null
                cleared = null
            }
        }
    }

    /**
     * How far the reveal wave has come at [seconds] into the reveal, as arrival: from 0 at the
     * exemplar to 1 at the last corner.
     */
    private fun front(seconds: Float) = easeOut((seconds / REVEAL).coerceAtMost(1f))

    /** When the reveal wave reaches [arrival], in seconds into the reveal; inverts [front]. */
    private fun reached(arrival: Float) = inverseEaseOut(arrival.coerceIn(0f, 1f)) * REVEAL

    /**
     * Fills [dim] and [glow] per heatmap cell as ARGB pixels: the dimming the wave has not lifted
     * yet, and the heatmap it has revealed as a glow in [color], condensing onto its peaks.
     */
    private fun revealPixels(heatmap: Heatmap, seconds: Float, color: Color) {
        val arrivals = arrivals ?: FloatArray(heatmap.values.size)
        if (dim.size != arrivals.size) {
            dim = IntArray(arrivals.size)
            glow = IntArray(arrivals.size)
        }
        val front = front(seconds)
        val condensed = ((seconds - REVEAL) / CONDENSE).coerceIn(0f, 1f)
        val gamma = GAMMA_FROM + (GAMMA_TO - GAMMA_FROM) * condensed
        val glowAlpha = GLOW_ALPHA * (1 - condensed)
        val rgb = color.toArgb() and 0xffffff
        for (cell in arrivals.indices) {
            val revealed = ((front - arrivals[cell]) / REVEAL_SOFTNESS).coerceIn(0f, 1f)
            dim[cell] = (DIM_ALPHA * (1 - revealed) * 255).roundToInt() shl 24
            val alpha = heatmap.values[cell].pow(gamma) * revealed * glowAlpha
            glow[cell] = ((alpha * 255).roundToInt() shl 24) or rgb
        }
    }

    /** The arrivals as a texture spanning [heatmapRect], interpolated between cell centers. */
    private fun arrivalShader(heatmapRect: Rect?): BitmapShader? {
        val arrivals = arrivals ?: return null
        heatmapRect ?: return null
        val grid = wave?.heatmap ?: return null
        val shader =
            arrivalShader
                ?: cellsShader(arrivals, grid.columns, grid.rows).also { arrivalShader = it }
        shader.stretchCells(grid.columns, grid.rows, heatmapRect)
        return shader
    }
}

/**
 * Scans while [state] is counting; reveals the count when counting ends with a heatmap, with a wave
 * from the edge of the exemplar across the crop; hides the points again, from the farthest in,
 * after [CountingAnimation.hide]. Each sweep of the scan and the reveal set off with a haptic tick.
 */
@Composable
fun rememberCountingAnimation(state: CountState): CountingAnimation {
    val animation = remember { CountingAnimation() }
    val shown = animation.shown(state)
    val heatmap = shown.heatmap
    val exemplar = shown.exemplar
    val crop = shown.crop
    val wave =
        remember(heatmap, exemplar, crop) {
            if (heatmap != null && exemplar != null && crop != null) Wave(heatmap, exemplar, crop)
            else null
        }
    SideEffect { animation.useWave(wave) }
    val haptics = LocalHapticFeedback.current
    // Only a count that arrives while this animation runs is revealed, not one already there.
    var wasCounting by remember { mutableStateOf(false) }
    val counting = state.counting
    LaunchedEffect(counting) {
        if (counting) {
            wasCounting = true
            // A faint tick as each sweep sets off.
            animation.scan { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
        } else {
            // A firmer click as the reveal sets off.
            animation.stopScanning(reveal = wasCounting && heatmap != null) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
            wasCounting = false
        }
    }
    val cleared = animation.cleared
    LaunchedEffect(cleared) { if (cleared != null) animation.hideCleared(cleared) }
    return animation
}

/** Calls [onFrame] every frame with the seconds since the first, until they pass [until]. */
private suspend fun everyFrame(
    until: Float = Float.POSITIVE_INFINITY,
    onFrame: (seconds: Float) -> Unit,
) {
    val start = withFrameNanos { it }
    do {
        val seconds = withFrameNanos { (it - start) / 1e9f }
        onFrame(seconds)
    } while (seconds < until)
}

/** The distance from the edge of [exemplar] to the farthest corner of [crop]. */
private fun reach(exemplar: Rect, crop: Rect) =
    listOf(crop.topLeft, crop.topRight, crop.bottomLeft, crop.bottomRight)
        .maxOf {
            Offset(
                    max(0f, max(exemplar.left - it.x, it.x - exemplar.right)),
                    max(0f, max(exemplar.top - it.y, it.y - exemplar.bottom)),
                )
                .getDistance()
        }
        .coerceAtLeast(1f)

/** From 0 up to 1 over the first [DIM_FADE] seconds of the scan. */
private fun fadeIn(seconds: Float) = (seconds / DIM_FADE).coerceAtMost(1f)

/** From 1 down to 0 as [fraction] goes from 0 to 1, keeping near full strength until late. */
private fun fadeLate(fraction: Float) = 1 - fraction.pow(4)

/** From 0 to 1 as [fraction] goes from 0 to 1, fast at first and slowing to a stop. */
internal fun easeOut(fraction: Float) = 1 - (1 - fraction) * (1 - fraction)

/** The fraction at which [easeOut] gives [eased]. */
internal fun inverseEaseOut(eased: Float) = 1 - sqrt(1 - eased)

/** From 0 to 1 as [fraction] goes from 0 to 1, overshooting past 1 before settling. */
private fun easeOutBack(fraction: Float): Float {
    val x = fraction - 1
    return 1 + (OVERSHOOT + 1) * x * x * x + OVERSHOOT * x * x
}
