package run.moritz.howmany

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import run.moritz.howmany.counting.Box

// The strongest this share of contours glows fully, and at most the strongest this share glows at
// all, which on a typical photo is about what fixed thresholds of 0.08 and 0.35 show.
private const val GLOWING_FULLY = 0.03f
private const val GLOWING = 0.33f
// Contours this much weaker than the fully glowing ones never glow, so the noise of photos with
// large even areas stays dark.
private const val FAINTEST = 0.23f
// Below this brightness change a photo has no contours to speak of.
private const val MIN_CONTRAST = 0.02f

/**
 * The brightness changes between pixels 1dp apart, from 0 to 1, from which on the shimmer lets
 * contours glow, and fully.
 */
data class ContourThresholds(val from: Float, val to: Float) {
    companion object {
        /** For a photo of typical contrast, or none to speak of. */
        val Default = ContourThresholds(0.08f, 0.35f)
    }
}

/**
 * Where the contours inside [crop] of this photo glow when it is shown at [dpPerPixel], so that
 * photos of any contrast glow about equally.
 */
fun Bitmap.contourThresholds(crop: Box, dpPerPixel: Float): ContourThresholds {
    val left = crop.left.roundToInt().coerceIn(0, width - 1)
    val top = crop.top.roundToInt().coerceIn(0, height - 1)
    val right = crop.right.roundToInt().coerceIn(left + 1, width)
    val bottom = crop.bottom.roundToInt().coerceIn(top + 1, height)
    // One pixel per dp, as the shimmer finds contours.
    val scaled =
        Bitmap.createBitmap(
            this,
            left,
            top,
            right - left,
            bottom - top,
            Matrix().apply { setScale(dpPerPixel, dpPerPixel) },
            true,
        )
    val pixels = IntArray(scaled.width * scaled.height)
    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
    val luma =
        FloatArray(pixels.size) { i ->
            val pixel = pixels[i]
            (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)) /
                255
        }
    return contourThresholds(luma, scaled.width, scaled.height).also {
        if (scaled !== this) scaled.recycle()
    }
}

/**
 * Where the contours of an image of [luma], row by row from 0 to 1, glow: its brightness changes
 * between neighbouring pixels, as percentiles.
 */
internal fun contourThresholds(luma: FloatArray, width: Int, height: Int): ContourThresholds {
    if (width < 3 || height < 3) return ContourThresholds.Default
    val gradients = FloatArray((width - 2) * (height - 2))
    var i = 0
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val gx = luma[y * width + x + 1] - luma[y * width + x - 1]
            val gy = luma[(y + 1) * width + x] - luma[(y - 1) * width + x]
            gradients[i++] = sqrt(gx * gx + gy * gy)
        }
    }
    gradients.sort()
    fun strongest(share: Float) = gradients[((1 - share) * (gradients.size - 1)).roundToInt()]
    val to = strongest(GLOWING_FULLY)
    if (to < MIN_CONTRAST) return ContourThresholds.Default
    val from = max(strongest(GLOWING), to * FAINTEST).takeIf { it < to } ?: (to * FAINTEST)
    return ContourThresholds(from, to)
}
