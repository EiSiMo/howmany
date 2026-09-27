package run.moritz.quantify

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import run.moritz.quantify.counting.Heatmap

// While counting, sonar rings run from the example across the crop.
private const val SCAN_PERIOD = 1.4f
private const val SCAN_RING_LIFE = 2.2f
private const val SCAN_RING_ALPHA = 0.5f
private val SCAN_BAND = 48.dp
private const val DIM_ALPHA = 0.3f
private const val DIM_FADE = 0.3f
// When the count arrives, one fast wave reveals the heatmap and pops the points up as it passes
// them; then the glow condenses onto its peaks and fades.
private const val REVEAL = 0.9f
private const val CONDENSE = 0.6f
private const val POP = 0.3f
private const val GLOW_ALPHA = 0.85f
private const val GAMMA_FROM = 1.5f
private const val GAMMA_TO = 10f
private const val GAMMA_STEPS = 16

/** The time since counting started and since its result arrived, driving what is drawn. */
@Stable
class CountingAnimation {
    internal var scanning by mutableStateOf<Float?>(null)
    internal var revealing by mutableStateOf<Float?>(null)

    private var glowFor: Heatmap? = null
    private val glows = mutableMapOf<Int, ImageBitmap>()

    /** The heatmap as a glow in [color], sharper the higher [step], cached per heatmap. */
    internal fun glow(heatmap: Heatmap, step: Int, color: Color): ImageBitmap {
        if (glowFor !== heatmap) {
            glows.clear()
            glowFor = heatmap
        }
        return glows.getOrPut(step) {
            val gamma = GAMMA_FROM + (GAMMA_TO - GAMMA_FROM) * step / GAMMA_STEPS
            val rgb = color.toArgb() and 0xffffff
            val pixels =
                IntArray(heatmap.values.size) {
                    val alpha = (heatmap.values[it].pow(gamma) * 255).roundToInt()
                    (alpha shl 24) or rgb
                }
            Bitmap.createBitmap(pixels, heatmap.gridSize, heatmap.gridSize, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
    }
}

/** Scans while [counting]; reveals the count when counting ends with a [heatmap]. */
@Composable
fun rememberCountingAnimation(counting: Boolean, heatmap: Heatmap?): CountingAnimation {
    val animation = remember { CountingAnimation() }
    var wasCounting by remember { mutableStateOf(false) }
    LaunchedEffect(counting) {
        if (counting) {
            wasCounting = true
            everyFrame { seconds -> animation.scanning = seconds }
        } else {
            animation.scanning = null
            if (wasCounting && heatmap != null) {
                everyFrame(until = REVEAL + CONDENSE) { seconds -> animation.revealing = seconds }
            }
            wasCounting = false
            animation.revealing = null
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
 * Draws the scan or the reveal inside [crop], all in view coordinates: rings from [origin], and the
 * [heatmap] placed at [heatmapRect] glowing in [glowColor].
 */
fun DrawScope.drawCountingAnimation(
    animation: CountingAnimation,
    origin: Offset,
    crop: Rect,
    heatmap: Heatmap?,
    heatmapRect: Rect?,
    glowColor: Color,
) {
    val reach = reach(origin, crop)
    clipRect(crop.left, crop.top, crop.right, crop.bottom) {
        animation.scanning?.let { seconds ->
            drawRect(
                Color.Black.copy(alpha = DIM_ALPHA * (seconds / DIM_FADE).coerceAtMost(1f)),
                crop.topLeft,
                crop.size,
            )
            var emitted = 0f
            while (emitted <= seconds) {
                val age = (seconds - emitted) / SCAN_RING_LIFE
                if (age < 1) {
                    drawRing(origin, easeOut(age) * reach, SCAN_RING_ALPHA * (1 - age).pow(1.5f))
                }
                emitted += SCAN_PERIOD
            }
        }
        animation.revealing?.let { seconds ->
            val wave = (seconds / REVEAL).coerceAtMost(1f)
            val front = easeOut(wave) * reach
            val condensed = ((seconds - REVEAL) / CONDENSE).coerceIn(0f, 1f)
            val frontPath = Path().apply { addOval(Rect(origin, front)) }
            clipPath(frontPath, ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = DIM_ALPHA), crop.topLeft, crop.size)
            }
            if (heatmap != null && heatmapRect != null) {
                val step = (condensed * GAMMA_STEPS).roundToInt()
                clipPath(frontPath) {
                    drawImage(
                        animation.glow(heatmap, step, glowColor),
                        srcSize = IntSize(heatmap.gridSize, heatmap.gridSize),
                        dstOffset =
                            IntOffset(heatmapRect.left.roundToInt(), heatmapRect.top.roundToInt()),
                        dstSize =
                            IntSize(
                                heatmapRect.width.roundToInt(),
                                heatmapRect.height.roundToInt(),
                            ),
                        alpha = GLOW_ALPHA * (1 - condensed),
                        blendMode = BlendMode.Screen,
                        filterQuality = FilterQuality.High,
                    )
                }
            }
            if (wave < 1) drawRing(origin, front, 1 - wave)
        }
    }
}

/**
 * How large to draw a point at [position] while the count is revealed from [origin]: 0 until the
 * wave reaches it, then popping up past 1 and settling at 1.
 */
fun pointScale(animation: CountingAnimation, origin: Offset, crop: Rect, position: Offset): Float {
    if (animation.scanning != null) return 0f
    val seconds = animation.revealing ?: return 1f
    val distance = ((position - origin).getDistance() / reach(origin, crop)).coerceIn(0f, 1f)
    // Inverts easeOut, when the wave front passes the point.
    val hit = (1 - sqrt(1 - distance)) * REVEAL
    val pop = (seconds - hit) / POP
    return if (pop <= 0) 0f else easeOutBack(pop.coerceAtMost(1f))
}

/** A soft bright band with a thin line at its outer edge, brightening the photo under it. */
private fun DrawScope.drawRing(center: Offset, radius: Float, alpha: Float) {
    if (radius <= 0 || alpha <= 0) return
    val band = SCAN_BAND.toPx()
    val outer = radius + band / 3
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent,
            max(0f, (radius - band) / outer) to Color.Transparent,
            radius / outer to Color.White.copy(alpha = alpha * 0.6f),
            1f to Color.Transparent,
            center = center,
            radius = outer,
        ),
        outer,
        center,
        blendMode = BlendMode.Screen,
    )
    drawCircle(Color.White.copy(alpha = alpha), radius, center, style = Stroke(1.5.dp.toPx()))
}

/** The distance from [origin] to the farthest corner of [crop]. */
private fun reach(origin: Offset, crop: Rect) =
    listOf(crop.topLeft, crop.topRight, crop.bottomLeft, crop.bottomRight)
        .maxOf { (it - origin).getDistance() }
        .coerceAtLeast(1f)

private fun easeOut(fraction: Float) = 1 - (1 - fraction) * (1 - fraction)

private fun easeOutBack(fraction: Float): Float {
    val overshoot = 1.70158f
    val x = fraction - 1
    return 1 + (overshoot + 1) * x * x * x + overshoot * x * x
}
