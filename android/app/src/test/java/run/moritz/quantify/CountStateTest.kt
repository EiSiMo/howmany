package run.moritz.quantify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.Point

class CountStateTest {
    private val detected = listOf(Point(100f, 100f), Point(500f, 500f))
    private val counted =
        CountState(crop = Box(0f, 0f, 1000f, 1000f), points = detected, detected = detected)

    @Test
    fun `is not corrected as counted`() {
        assertFalse(counted.corrected)
    }

    @Test
    fun `is corrected once a point is added or removed`() {
        assertTrue(counted.copy(points = detected + Point(800f, 800f)).corrected)
        assertTrue(counted.copy(points = detected.drop(1)).corrected)
    }

    @Test
    fun `is not corrected when a point is added and removed again`() {
        val points =
            detected.toggled(Point(800f, 800f), 20f, 20f).toggled(Point(800f, 800f), 20f, 20f)
        assertFalse(counted.copy(points = points).corrected)
    }

    @Test
    fun `is corrected when the crop cuts off a point`() {
        assertTrue(counted.copy(crop = Box(0f, 0f, 400f, 400f)).corrected)
    }

    @Test
    fun `goes from marking to counted`() {
        val marking = CountState(crop = Box(0f, 0f, 1000f, 1000f))
        val ready = marking.copy(exemplar = Box(100f, 100f, 150f, 150f))
        assertEquals(CountPhase.Empty, CountState().phase)
        assertEquals(CountPhase.Marking, marking.phase)
        assertEquals(CountPhase.Ready, ready.phase)
        assertEquals(CountPhase.Counting, ready.copy(counting = true).phase)
        assertEquals(CountPhase.Counted, ready.copy(points = detected, detected = detected).phase)
    }
}
