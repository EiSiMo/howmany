package run.moritz.howmany

import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContoursTest {
    private val side = 64

    /** Blobs of brightness from 0.5 - [contrast] to 0.5 + [contrast]. */
    private fun blobs(contrast: Float) =
        FloatArray(side * side) { i ->
            0.5f + contrast * sin(i % side / 3f) * sin(i / side / 5f)
        }

    @Test
    fun `scales with the contrast of the photo`() {
        val low = contourThresholds(blobs(0.05f), side, side)
        val high = contourThresholds(blobs(0.4f), side, side)

        assertEquals(8f, high.from / low.from, 0.01f)
        assertEquals(8f, high.to / low.to, 0.01f)
        assertTrue(high.from < high.to)
    }

    @Test
    fun `glows fully only at the strongest contours`() {
        val luma = blobs(0.4f)
        val thresholds = contourThresholds(luma, side, side)

        val gradients = gradients(luma)
        val above = gradients.count { it >= thresholds.to }.toFloat() / gradients.size
        assertEquals(0.03f, above, 0.01f)
    }

    @Test
    fun `keeps the noise of large even areas dark`() {
        // Blobs in the top tenth, faint noise below.
        val luma = blobs(0.4f)
        for (i in side * side / 10 until side * side) luma[i] = 0.5f + 0.005f * (i % 3)
        val thresholds = contourThresholds(luma, side, side)

        assertTrue(thresholds.from > 0.2f * thresholds.to)
        assertTrue(thresholds.from > 0.02f)
    }

    @Test
    fun `falls back on a flat photo`() {
        val flat = FloatArray(side * side) { 0.5f }

        assertEquals(ContourThresholds.Default, contourThresholds(flat, side, side))
    }

    @Test
    fun `falls back on a photo too small to have contours`() {
        assertEquals(ContourThresholds.Default, contourThresholds(FloatArray(2) { it * 1f }, 2, 1))
    }

    /** Central differences of [luma] inside its border, like the shimmer's. */
    private fun gradients(luma: FloatArray) = buildList {
        for (y in 1 until side - 1) for (x in 1 until side - 1) {
            val gx = luma[y * side + x + 1] - luma[y * side + x - 1]
            val gy = luma[(y + 1) * side + x] - luma[(y - 1) * side + x]
            add(sqrt(gx * gx + gy * gy))
        }
    }
}
