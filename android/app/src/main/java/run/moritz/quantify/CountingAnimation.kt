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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.ShortBuffer
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
private val SHIMMER = 2.dp
private val SHIMMER_BLOB = 80.dp
private val EDGE_STEP = 1.dp
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
// The waves bend the photo like water: pixels shift by up to about this much where a ring passes,
// easing in and out over about this width on either side so the ring has no visible edges.
private val DISTORTION = 3.dp
private val DISTORTION_WIDTH = 96.dp
private const val FRONT_BRIGHTNESS = 0.06f

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Distortion() else null
    }
    private val shimmer by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Shimmer() else null
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
            return with(density) {
                shimmer.effect(
                    exemplar,
                    crop,
                    reach(exemplar, crop),
                    seconds,
                    SHIMMER.toPx(),
                    SHIMMER_BLOB.toPx(),
                    EDGE_STEP.toPx(),
                )
            }
        }
        val distortion = distortion ?: return null
        val revealing = revealing
        if (revealing == null || revealing >= REVEAL) return null
        val reach = reach(exemplar, crop)
        val rings = listOf(front(revealing) * reach to fadeLate(revealing / REVEAL))
        val arrival = arrivalShader(heatmapRect) ?: return null
        return distortion.effect(
            exemplar,
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

/**
 * Bends the photo along the waves like light through water: pixels move towards or away from the
 * exemplar where a ring passes, colors split slightly, and the reveal's front shines. The exemplar
 * itself stays still.
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
     * times [reach]; without, they run out from the edge of [exemplar].
     */
    fun effect(
        exemplar: Rect,
        crop: Rect,
        rings: List<Pair<Float, Float>>,
        arrival: BitmapShader?,
        reach: Float,
        amplitude: Float,
        width: Float,
    ): RenderEffect {
        val first = rings.getOrElse(0) { 0f to 0f }
        val second = rings.getOrElse(1) { 0f to 0f }
        shader.setFloatUniform(
            "exemplar",
            exemplar.left,
            exemplar.top,
            exemplar.right,
            exemplar.bottom,
        )
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
            uniform float4 exemplar;
            uniform float4 crop;
            uniform float4 rings;
            uniform float reveal;
            uniform float reach;
            uniform float amplitude;
            uniform float width;
            uniform float brightness;

            // From the exemplar's edge out to p; zero inside the exemplar.
            float2 outwards(float2 p) {
                return max(max(exemplar.xy - p, p - exemplar.zw), float2(0));
            }

            // How far the wave has come to p, in pixels.
            float field(float2 p) {
                if (reveal > 0.5) return arrival.eval(p).r * reach;
                return length(outwards(p));
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
                    direction = outwards(p) * sign(p - (exemplar.xy + exemplar.zw) * 0.5);
                }
                float size = length(direction);
                direction = size > 0.0001 ? direction / size : float2(0);
                float push = rings.y * crest(d - rings.x) + rings.w * crest(d - rings.z);
                // Fades in from the exemplar's edge, so the exemplar stays still without a seam.
                push *= smoothstep(0.0, width * 0.5, length(outwards(p)));
                float2 offset = direction * push * amplitude * 2.3;
                half4 color = content.eval(p - offset);
                color.r = content.eval(p - offset * 1.1).r;
                color.b = content.eval(p - offset * 0.9).b;
                float behind = (d - rings.x) / width;
                float shine = rings.y * exp(-behind * behind) * brightness;
                color.rgb += half3(shine) * color.a;
                return color;
            }
            """
    }
}

/**
 * Lets the photo shimmer while it is analysed: it wobbles slightly along slowly drifting noise,
 * except for the exemplar, its contours glow faintly, and brightly where a soft band of light
 * sweeps diagonally across the crop.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Shimmer {
    private val shader = RuntimeShader(SHADER)

    /**
     * At [seconds] into the scan: pixels shift by up to [amplitude] in noise blobs about [blob]
     * wide, contours are found between pixels [spacing] apart, and the band of light spreads from
     * [exemplar] to [reach] away from it.
     */
    fun effect(
        exemplar: Rect,
        crop: Rect,
        reach: Float,
        seconds: Float,
        amplitude: Float,
        blob: Float,
        spacing: Float,
    ): RenderEffect {
        shader.setFloatUniform(
            "exemplar",
            exemplar.left,
            exemplar.top,
            exemplar.right,
            exemplar.bottom,
        )
        shader.setFloatUniform("crop", crop.left, crop.top, crop.right, crop.bottom)
        shader.setFloatUniform("reach", reach)
        shader.setFloatUniform("time", seconds)
        shader.setFloatUniform("period", SCAN_PERIOD)
        shader.setFloatUniform("fade", (seconds / DIM_FADE).coerceAtMost(1f))
        shader.setFloatUniform("amplitude", amplitude)
        shader.setFloatUniform("blob", blob)
        shader.setFloatUniform("spacing", spacing)
        return AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }

    private companion object {
        const val SHADER =
            """
            uniform shader content;
            uniform float4 exemplar;
            uniform float4 crop;
            uniform float reach;
            uniform float time;
            uniform float period;
            uniform float fade;
            uniform float amplitude;
            uniform float blob;
            uniform float spacing;

            float hash(float2 p) {
                return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }

            // Smooth value noise between 0 and 1.
            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + float2(1, 0)), u.x),
                           mix(hash(i + float2(0, 1)), hash(i + float2(1, 1)), u.x), u.y);
            }

            float luma(float2 p) {
                return dot(content.eval(p).rgb, half3(0.299, 0.587, 0.114));
            }

            half4 main(float2 p) {
                if (p.x < crop.x || p.y < crop.y || p.x > crop.z || p.y > crop.w) {
                    return content.eval(p);
                }
                // Wobble, calming towards the exemplar so the exemplar stays still without a seam.
                float2 outwards = max(max(exemplar.xy - p, p - exemplar.zw), float2(0));
                float calm = smoothstep(0.0, blob * 0.5, length(outwards));
                float2 q = p / blob;
                float2 n = float2(noise(q + time * 0.3), noise(q + 17.0 - time * 0.3)) - 0.5;
                float2 s = p + n * 2.0 * amplitude * calm * fade;
                half4 color = content.eval(s);

                // Contours: how sharply the brightness changes around s.
                float gx = luma(s + float2(spacing, 0)) - luma(s - float2(spacing, 0));
                float gy = luma(s + float2(0, spacing)) - luma(s - float2(0, spacing));
                float edge = smoothstep(0.08, 0.35, length(float2(gx, gy)));

                // The band spreads as a ring from inside the exemplar to past the farthest corner.
                float d = length(outwards) / reach - (fract(time / period) * 2.0 - 0.5);
                float band = exp(-d * d * 25.0);

                color.rgb += half3(0.85, 0.92, 1.0) * edge * (0.12 + band * 0.6) * 0.35 * fade * color.a;
                color.rgb += half3(band * 0.02 * fade) * color.a;
                return color;
            }
            """
    }
}
