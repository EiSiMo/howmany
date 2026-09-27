package run.moritz.quantify

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.Heatmap
import run.moritz.quantify.counting.Point

// While counting, sonar rings run from the example across the crop.
private const val SCAN_PERIOD = 1.4f
private const val SCAN_RING_LIFE = 2.2f
private const val SCAN_RING_ALPHA = 0.5f
private val SCAN_BAND = 48.dp
private const val DIM_ALPHA = 0.3f
private const val DIM_FADE = 0.3f
// When the count arrives, one fast wave reveals the heatmap and pops the points up as it reaches
// them, slowed down by the objects; then the glow condenses onto its peaks and fades.
private const val REVEAL = 0.9f
private const val CONDENSE = 0.6f
private const val POP = 0.3f
// How far behind the wave front, as a fraction of the whole way, a place is fully revealed.
private const val REVEAL_SOFTNESS = 0.04f
private const val GLOW_ALPHA = 0.85f
private const val GAMMA_FROM = 1.5f
private const val GAMMA_TO = 10f
// The waves bend the photo like water: pixels shift by up to about this much where a ring passes.
private val DISTORTION = 9.dp
private val DISTORTION_WIDTH = 28.dp
private const val FRONT_BRIGHTNESS = 0.25f

/**
 * The time since counting started and since its result arrived, and the [wave] that reveals the
 * result, driving what is drawn.
 */
@Stable
class CountingAnimation {
    internal var scanning by mutableStateOf<Float?>(null)
    internal var revealing by mutableStateOf<Float?>(null)
    internal var wave: Wave? = null
        set(value) {
            field = value
            arrivals = value?.arrivals()
            arrivalShader = null
        }

    private var arrivals: FloatArray? = null
    private var arrivalShader: BitmapShader? = null
    private val distortion by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Distortion() else null
    }

    /**
     * How far the reveal wave has come, as arrival: from 0 at the origin to 1 at the last corner.
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
     * The render effect that bends the photo where a wave passes, with the scan's rings from
     * [origin] or the reveal's front bent by the objects, in view coordinates; null while there is
     * no wave or the device cannot run shaders.
     */
    internal fun distortion(
        origin: Offset,
        crop: Rect,
        heatmapRect: Rect?,
        density: Density,
    ): RenderEffect? {
        val distortion = distortion ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val reach = reach(origin, crop)
        val scanning = scanning
        val revealing = revealing
        val rings =
            when {
                scanning != null -> scanRings(scanning, reach)
                revealing != null && revealing < REVEAL ->
                    listOf(front(revealing) * reach to 1 - revealing / REVEAL)
                else -> return null
            }
        val arrival = if (revealing != null) arrivalShader(heatmapRect) else null
        if (revealing != null && arrival == null) return null
        return distortion.effect(
            origin,
            crop,
            rings,
            arrival,
            reach,
            with(density) { DISTORTION.toPx() },
            with(density) { DISTORTION_WIDTH.toPx() },
        )
    }

    /** The arrivals as a texture spanning [heatmapRect], interpolated between cell centers. */
    // Lint takes the half floats for plain shorts, but they go straight into a half float bitmap.
    @SuppressLint("HalfFloat")
    private fun arrivalShader(heatmapRect: Rect?): BitmapShader? {
        val arrivals = arrivals ?: return null
        heatmapRect ?: return null
        val grid = wave?.heatmap ?: return null
        val shader =
            arrivalShader
                ?: run {
                    val halves = ShortArray(arrivals.size * 4)
                    for (cell in arrivals.indices) {
                        halves.fill(Half.toHalf(arrivals[cell]), cell * 4, cell * 4 + 4)
                    }
                    val bitmap =
                        Bitmap.createBitmap(grid.columns, grid.rows, Bitmap.Config.RGBA_F16)
                    bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(halves))
                    BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            it.filterMode = BitmapShader.FILTER_MODE_LINEAR
                        }
                        arrivalShader = it
                    }
                }
        shader.setLocalMatrix(cellsTo(heatmapRect, grid.columns, grid.rows))
        return shader
    }
}

/**
 * Scans while [counting]; reveals the count when counting ends with a [heatmap], with a wave from
 * [origin] that is slowed down by the objects in [crop].
 */
@Composable
fun rememberCountingAnimation(
    counting: Boolean,
    heatmap: Heatmap?,
    origin: Point?,
    crop: Box?,
): CountingAnimation {
    val animation = remember { CountingAnimation() }
    val wave =
        remember(heatmap, origin, crop) {
            if (heatmap != null && origin != null && crop != null) Wave(heatmap, origin, crop)
            else null
        }
    if (animation.wave !== wave) animation.wave = wave
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
 * [heatmap] placed at [heatmapRect] glowing in [glowColor] where the wave has passed.
 */
fun DrawScope.drawCountingAnimation(
    animation: CountingAnimation,
    origin: Offset,
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
            for ((radius, strength) in scanRings(seconds, reach(origin, crop))) {
                drawRing(origin, radius, SCAN_RING_ALPHA * strength)
            }
        }
        val seconds = animation.revealing
        if (seconds != null && heatmap != null && heatmapRect != null) {
            val (dim, glow) = animation.revealPixels(heatmap, seconds, glowColor)
            drawRect(cellsBrush(dim, heatmap, heatmapRect), crop.topLeft, crop.size)
            drawRect(
                cellsBrush(glow, heatmap, heatmapRect),
                crop.topLeft,
                crop.size,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

/**
 * How large to draw a counted point at [point] in image pixels while the count is revealed: 0 until
 * the wave reaches it, then popping up past 1 and settling at 1.
 */
fun pointScale(animation: CountingAnimation, point: Point): Float {
    if (animation.scanning != null) return 0f
    val seconds = animation.revealing ?: return 1f
    val wave = animation.wave ?: return 1f
    // Inverts easeOut, when the wave front reaches the point.
    val hit = (1 - sqrt(1 - wave.arrival(point).coerceIn(0f, 1f))) * REVEAL
    val pop = (seconds - hit) / POP
    return if (pop <= 0) 0f else easeOutBack(pop.coerceAtMost(1f))
}

/** The scan's rings at [seconds]: each with its radius and a strength fading from 1 to 0. */
private fun scanRings(seconds: Float, reach: Float): List<Pair<Float, Float>> {
    val rings = mutableListOf<Pair<Float, Float>>()
    var emitted = 0f
    while (emitted <= seconds) {
        val age = (seconds - emitted) / SCAN_RING_LIFE
        if (age < 1) rings += easeOut(age) * reach to (1 - age).pow(1.5f)
        emitted += SCAN_PERIOD
    }
    return rings
}

/** Per-cell ARGB [pixels] of a square grid, stretched smoothly over [rect] and clamped beyond. */
private fun cellsBrush(pixels: IntArray, heatmap: Heatmap, rect: Rect): Brush {
    val bitmap = Bitmap.createBitmap(pixels, heatmap.columns, heatmap.rows, Bitmap.Config.ARGB_8888)
    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        shader.filterMode = BitmapShader.FILTER_MODE_LINEAR
    }
    shader.setLocalMatrix(cellsTo(rect, heatmap.columns, heatmap.rows))
    return ShaderBrush(shader)
}

/** Maps a grid of [columns] x [rows] cells onto [rect]. */
private fun cellsTo(rect: Rect, columns: Int, rows: Int) =
    Matrix().apply {
        setScale(rect.width / columns, rect.height / rows)
        postTranslate(rect.left, rect.top)
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

/**
 * Bends the photo along the waves like light through water: pixels move towards or away from the
 * origin where a ring passes, colors split slightly, and the reveal's front shines.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Distortion {
    private val shader = RuntimeShader(SHADER)
    // Scanning has no arrivals; the shader still needs some input.
    private val none =
        BitmapShader(
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP,
        )

    /**
     * With [arrival], the rings (radius, strength; at most two) are the reveal's front in arrival
     * times [reach]; without, they are circles around [origin].
     */
    fun effect(
        origin: Offset,
        crop: Rect,
        rings: List<Pair<Float, Float>>,
        arrival: BitmapShader?,
        reach: Float,
        amplitude: Float,
        width: Float,
    ): RenderEffect {
        val first = rings.getOrElse(0) { 0f to 0f }
        val second = rings.getOrElse(1) { 0f to 0f }
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("crop", crop.left, crop.top, crop.right, crop.bottom)
        shader.setFloatUniform("rings", first.first, first.second, second.first, second.second)
        shader.setFloatUniform("reveal", if (arrival != null) 1f else 0f)
        shader.setFloatUniform("reach", reach)
        shader.setFloatUniform("amplitude", amplitude)
        shader.setFloatUniform("width", width)
        shader.setFloatUniform("brightness", if (arrival != null) FRONT_BRIGHTNESS else 0f)
        shader.setInputShader("arrival", arrival ?: none)
        return AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }

    private companion object {
        const val SHADER =
            """
            uniform shader content;
            uniform shader arrival;
            uniform float2 origin;
            uniform float4 crop;
            uniform float4 rings;
            uniform float reveal;
            uniform float reach;
            uniform float amplitude;
            uniform float width;
            uniform float brightness;

            // How far the wave has come to p, in pixels.
            float field(float2 p) {
                if (reveal > 0.5) return arrival.eval(p).r * reach;
                return distance(p, origin);
            }

            // One wave crest: pushes outwards just ahead of the ring, inwards just behind it.
            float crest(float d) {
                float x = d / width;
                return x * exp(-x * x);
            }

            half4 main(float2 p) {
                if (p.x < crop.x || p.y < crop.y || p.x > crop.z || p.y > crop.w) {
                    return content.eval(p);
                }
                float d = field(p);
                float2 direction;
                if (reveal > 0.5) {
                    float e = 2.0;
                    direction = float2(field(p + float2(e, 0)) - field(p - float2(e, 0)),
                                       field(p + float2(0, e)) - field(p - float2(0, e)));
                } else {
                    direction = p - origin;
                }
                float size = length(direction);
                direction = size > 0.0001 ? direction / size : float2(0);
                float push = rings.y * crest(d - rings.x) + rings.w * crest(d - rings.z);
                float2 offset = direction * push * amplitude * 2.3;
                half4 color = content.eval(p - offset);
                color.r = content.eval(p - offset * 1.25).r;
                color.b = content.eval(p - offset * 0.75).b;
                float behind = (d - rings.x) / width;
                float shine = rings.y * exp(-behind * behind) * brightness;
                color.rgb += half3(shine) * color.a;
                return color;
            }
            """
    }
}
