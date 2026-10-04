package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Heatmap
import run.moritz.howmany.counting.Point

class WaveTest {
    // 16 x 16 cells of 10 image pixels, cell (0, 0) centered on the origin.
    private val bounds = Box(-5f, -5f, 155f, 155f)
    private val crop = Box(0f, 0f, 150f, 150f)

    @Test
    fun `spreads evenly without objects and reaches the farthest crop corner last`() {
        val wave = Wave(Heatmap(16, 16, FloatArray(256), bounds), listOf(Box(0f, 0f, 0f, 0f)), crop)

        assertEquals(0f, wave.arrival(Point(0f, 0f)), 0.01f)
        assertEquals(0.5f, wave.arrival(Point(75f, 75f)), 0.01f)
        assertEquals(wave.arrival(Point(100f, 0f)), wave.arrival(Point(0f, 100f)), 0.01f)
        assertEquals(1f, wave.arrival(Point(150f, 150f)), 0.01f)
    }

    @Test
    fun `starts at the edge of the exemplar and leaves it untouched`() {
        val exemplar = Box(50f, 50f, 100f, 100f)
        val wave = Wave(Heatmap(16, 16, FloatArray(256), bounds), listOf(exemplar), crop)

        assertEquals(0f, wave.arrival(Point(75f, 75f)), 0.001f)
        assertEquals(0f, wave.arrival(Point(100f, 60f)), 0.001f)
        assertEquals(wave.arrival(Point(75f, 0f)), wave.arrival(Point(150f, 75f)), 0.001f)
        assertEquals(1f, wave.arrival(Point(0f, 0f)), 0.001f)
    }

    @Test
    fun `arrives from the nearest of several exemplars`() {
        val left = Box(0f, 0f, 10f, 10f)
        val right = Box(140f, 140f, 150f, 150f)
        val wave = Wave(Heatmap(16, 16, FloatArray(256), bounds), listOf(left, right), crop)

        assertEquals(wave.arrival(Point(5f, 5f)), wave.arrival(Point(145f, 145f)), 0.001f)
        assertTrue(wave.arrival(Point(5f, 5f)) < wave.arrival(Point(75f, 75f)))
        assertTrue(wave.arrival(Point(145f, 145f)) < wave.arrival(Point(75f, 75f)))
    }

    @Test
    fun `runs at the same speed through objects`() {
        val values = FloatArray(256)
        // An object between the origin and the right edge, none towards the bottom.
        for (row in 0..1) for (column in 5..7) values[row * 16 + column] = 1f
        val wave = Wave(Heatmap(16, 16, values, bounds), listOf(Box(0f, 0f, 0f, 0f)), crop)

        assertEquals(wave.arrival(Point(0f, 120f)), wave.arrival(Point(120f, 0f)), 0.001f)
    }

    @Test
    fun `gives an arrival per cell on a grid wider than tall`() {
        // 8 x 16 cells of 10 image pixels, cell (0, 0) centered on the origin.
        val heatmap = Heatmap(8, 16, FloatArray(8 * 16), Box(-5f, -5f, 155f, 75f))
        val wave = Wave(heatmap, listOf(Box(0f, 0f, 0f, 0f)), Box(0f, 0f, 150f, 70f))

        val arrivals = wave.arrivals()

        assertEquals(8 * 16, arrivals.size)
        assertEquals(wave.arrival(Point(140f, 70f)), arrivals[7 * 16 + 14], 0.001f)
    }
}
