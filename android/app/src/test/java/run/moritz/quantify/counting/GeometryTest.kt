package run.moritz.quantify.counting

import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
    private val box = Box(10f, 20f, 40f, 60f)

    @Test
    fun `measures the distance from the edge of a box, zero inside`() {
        assertEquals(0f, box.distanceTo(Point(25f, 40f)), 0f)
        assertEquals(0f, box.distanceTo(Point(40f, 20f)), 0f)
        assertEquals(5f, box.distanceTo(Point(5f, 30f)), 1e-6f)
        assertEquals(7f, box.distanceTo(Point(30f, 67f)), 1e-6f)
        assertEquals(5f, box.distanceTo(Point(43f, 16f)), 1e-6f)
    }

    @Test
    fun `lists the four corners of a box`() {
        assertEquals(
            setOf(Point(10f, 20f), Point(40f, 20f), Point(10f, 60f), Point(40f, 60f)),
            box.corners.toSet(),
        )
    }
}
