package run.moritz.quantify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.Heatmap
import run.moritz.quantify.counting.Point

class WaveTest {
    // 16 x 16 cells of 10 image pixels, cell (0, 0) centered on the origin.
    private val bounds = Box(-5f, -5f, 155f, 155f)
    private val crop = Box(0f, 0f, 150f, 150f)

    @Test
    fun `spreads evenly without objects and reaches the farthest crop corner last`() {
        val wave = Wave(Heatmap(16, 16, FloatArray(256), bounds), Point(0f, 0f), crop)

        assertEquals(0f, wave.arrival(Point(0f, 0f)), 0.01f)
        assertEquals(0.5f, wave.arrival(Point(75f, 75f)), 0.01f)
        assertEquals(wave.arrival(Point(100f, 0f)), wave.arrival(Point(0f, 100f)), 0.01f)
        assertEquals(1f, wave.arrival(Point(150f, 150f)), 0.01f)
    }

    @Test
    fun `is slowed down by objects on its way`() {
        val values = FloatArray(256)
        // An object between the origin and the right edge, none towards the bottom.
        for (row in 0..1) for (column in 5..7) values[row * 16 + column] = 1f
        val wave = Wave(Heatmap(16, 16, values, bounds), Point(0f, 0f), crop)

        assertTrue(wave.arrival(Point(120f, 0f)) > wave.arrival(Point(0f, 120f)) * 1.2f)
    }

    @Test
    fun `works on a grid wider than tall`() {
        // 8 x 16 cells of 10 image pixels, an object in the bottom right cells.
        val values = FloatArray(8 * 16)
        for (column in 12..15) values[7 * 16 + column] = 1f
        val wave =
            Wave(
                Heatmap(8, 16, values, Box(-5f, -5f, 155f, 75f)),
                Point(0f, 0f),
                Box(0f, 0f, 150f, 70f),
            )

        assertEquals(8 * 16, wave.arrivals().size)
        assertTrue(wave.arrivals()[7 * 16 + 14] > wave.arrivals()[0 * 16 + 14])
    }
}
