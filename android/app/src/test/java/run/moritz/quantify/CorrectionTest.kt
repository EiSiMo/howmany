package run.moritz.quantify

import org.junit.Assert.assertEquals
import org.junit.Test
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.Point

class CorrectionTest {
    private val points = listOf(Point(100f, 100f), Point(130f, 100f), Point(500f, 500f))

    @Test
    fun `removes the point that was tapped`() {
        assertEquals(
            listOf(Point(100f, 100f), Point(130f, 100f)),
            points.toggled(Point(505f, 495f), hitRadius = 20f),
        )
    }

    @Test
    fun `removes only the nearest of several hit points`() {
        assertEquals(
            listOf(Point(100f, 100f), Point(500f, 500f)),
            points.toggled(Point(120f, 100f), hitRadius = 20f),
        )
    }

    @Test
    fun `adds a point where nothing was hit`() {
        assertEquals(points + Point(300f, 300f), points.toggled(Point(300f, 300f), hitRadius = 20f))
    }

    @Test
    fun `ignores points outside the crop and taps outside it`() {
        val crop = Box(0f, 0f, 125f, 1000f)

        assertEquals(
            listOf(Point(130f, 100f), Point(500f, 500f)),
            points.toggled(Point(124f, 100f), hitRadius = 30f, crop = crop),
        )
        assertEquals(points, points.toggled(Point(300f, 300f), hitRadius = 20f, crop = crop))
    }
}
