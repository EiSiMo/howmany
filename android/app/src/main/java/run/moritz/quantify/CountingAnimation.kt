package run.moritz.quantify

import android.graphics.BitmapShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
// SCAN_PERIOD, lighting up the contours it passes.
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
private const val GLOW_ALPHA = 0.65f
private const val GAMMA_FROM = 1.5f
private const val GAMMA_TO = 10f

/**
 * The time since counting started, since its result arrived and since it was cleared, and the
 * [wave] that reveals the result, driving what is drawn.
 */
@Stable
class CountingAnimation {
    internal var scanning by mutableStateOf<Float?>(null)
    internal var revealing by mutableStateOf<Float?>(null)
    internal var hiding by mutableStateOf<Float?>(null)

    internal var cleared by mutableStateOf<CountState?>(null)
    /** The arrival of the cleared count's farthest point, where hiding begins. */
    internal var farthest = 1f

    internal var wave: Wave? = null
        set(value) {
            field = value
            arrivals = value?.arrivals()
            arrivalShader = null
        }

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

    private var arrivals: FloatArray? = null
    private var arrivalShader: BitmapShader? = null
    private val distortion by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) DistortionShader() else null
    }
    private val shimmer by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ShimmerShader() else null
    }

    /**
     * How far the reveal wave has come, as arrival: from 0 at the exemplar to 1 at the last corner.
     */
    internal fun front(seconds: Float) = easeOut((seconds / REVEAL).coerceAtMost(1f))

    /**
     * Per heatmap cell as ARGB pixels: the dimming the wave has not lifted yet, and the heatmap it
     * has revealed as a glow in [color], condensing onto its peaks.
     */
    internal fun revealPixels(
        heatmap: Heatmap,
        seconds: Float,
        color: Color,
    ): Pair<IntArray, IntArray> {
        val arrivals = arrivals ?: FloatArray(heatmap.values.size)
        val front = front(seconds)
        val condensed = ((seconds - REVEAL) / CONDENSE).coerceIn(0f, 1f)
        val gamma = GAMMA_FROM + (GAMMA_TO - GAMMA_FROM) * condensed
        val glowAlpha = GLOW_ALPHA * (1 - condensed)
        val rgb = color.toArgb() and 0xffffff
        val dim = IntArray(arrivals.size)
        val glow = IntArray(arrivals.size)
        for (cell in arrivals.indices) {
            val revealed = ((front - arrivals[cell]) / REVEAL_SOFTNESS).coerceIn(0f, 1f)
            dim[cell] = (DIM_ALPHA * (1 - revealed) * 255).roundToInt() shl 24
            val alpha = heatmap.values[cell].pow(gamma) * revealed * glowAlpha
            glow[cell] = ((alpha * 255).roundToInt() shl 24) or rgb
        }
        return dim to glow
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
            val shimmer = shimmer ?: return null
            return shimmer.effect(
                exemplar,
                crop,
                reach(exemplar, crop),
                seconds,
                SCAN_PERIOD,
                (seconds / DIM_FADE).coerceAtMost(1f),
                density,
            )
        }
        val distortion = distortion ?: return null
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
    if (animation.wave !== wave) animation.wave = wave
    val haptics = LocalHapticFeedback.current
    var wasCounting by remember { mutableStateOf(false) }
    val counting = state.counting
    LaunchedEffect(counting) {
        if (counting) {
            wasCounting = true
            var sweeps = 0
            everyFrame { seconds ->
                animation.scanning = seconds
                // A faint tick as each sweep sets off.
                if (seconds >= sweeps * SCAN_PERIOD) {
                    sweeps++
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
        } else {
            animation.scanning = null
            if (wasCounting && heatmap != null) {
                // A firmer click as the reveal sets off.
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                everyFrame(until = REVEAL + CONDENSE) { seconds -> animation.revealing = seconds }
            }
            wasCounting = false
            animation.revealing = null
        }
    }
    val cleared = animation.cleared
    LaunchedEffect(cleared) {
        if (cleared != null) {
            try {
                everyFrame(until = HIDE + SHRINK) { seconds -> animation.hiding = seconds }
            } finally {
                if (animation.cleared === cleared) {
                    animation.hiding = null
                    animation.cleared = null
                }
            }
        }
    }
    return animation
}

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

/**
 * Draws the scan's dimming or the reveal inside [crop], all in view coordinates: the [heatmap]
 * placed at [heatmapRect] glowing in [glowColor] where the wave has passed.
 */
fun DrawScope.drawCountingAnimation(
    animation: CountingAnimation,
    crop: Rect,
    heatmap: Heatmap?,
    heatmapRect: Rect?,
    glowColor: Color,
) {
    clipRect(crop.left, crop.top, crop.right, crop.bottom) {
        animation.scanning?.let { seconds ->
            drawRect(
                Color.Black.copy(alpha = DIM_ALPHA * (seconds / DIM_FADE).coerceAtMost(1f)),
                crop.topLeft,
                crop.size,
            )
        }
        val seconds = animation.revealing
        if (seconds != null && heatmap != null && heatmapRect != null) {
            val (dim, glow) = animation.revealPixels(heatmap, seconds, glowColor)
            drawRect(
                cellsBrush(dim, heatmap.columns, heatmap.rows, heatmapRect),
                crop.topLeft,
                crop.size,
            )
            drawRect(
                cellsBrush(glow, heatmap.columns, heatmap.rows, heatmapRect),
                crop.topLeft,
                crop.size,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

/**
 * How large to draw a counted point at [point] in image pixels while the count is revealed: 0 until
 * the wave reaches it, then popping up past 1 and settling at 1; while it is cleared, shrinking
 * back to 0 as the wave returns to the exemplar.
 */
fun pointScale(animation: CountingAnimation, point: Point): Float {
    if (animation.scanning != null) return 0f
    animation.hiding?.let { seconds ->
        val wave = animation.wave ?: return 0f
        val hit = (1 - wave.arrival(point) / animation.farthest).coerceIn(0f, 1f) * HIDE
        val left = 1 - ((seconds - hit) / SHRINK).coerceIn(0f, 1f)
        // Shrinking fastest at first, so it visibly starts at once.
        return left * left
    }
    val seconds = animation.revealing ?: return 1f
    val wave = animation.wave ?: return 1f
    // Inverts easeOut, when the wave front reaches the point.
    val hit = (1 - sqrt(1 - wave.arrival(point).coerceIn(0f, 1f))) * REVEAL
    val pop = (seconds - hit) / POP
    return if (pop <= 0) 0f else easeOutBack(pop.coerceAtMost(1f))
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

/** From 1 down to 0 as [fraction] goes from 0 to 1, keeping near full strength until late. */
private fun fadeLate(fraction: Float) = 1 - fraction.pow(4)

private fun easeOut(fraction: Float) = 1 - (1 - fraction) * (1 - fraction)

private fun easeOutBack(fraction: Float): Float {
    val overshoot = 1.70158f
    val x = fraction - 1
    return 1 + (overshoot + 1) * x * x * x + overshoot * x * x
}
