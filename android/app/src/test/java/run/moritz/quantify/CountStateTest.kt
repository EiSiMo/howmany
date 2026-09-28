package run.moritz.quantify

import org.junit.Assert.assertEquals
import org.junit.Test
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.CountResult
import run.moritz.quantify.counting.Detection
import run.moritz.quantify.counting.Heatmap
import run.moritz.quantify.counting.Point

class CountStateTest {
    private val detected = listOf(Point(100f, 100f), Point(500f, 500f))

    @Test
    fun `goes from marking to counted`() {
        val marking = CountState(crop = Box(0f, 0f, 1000f, 1000f))
        val ready = marking.copy(exemplar = Box(100f, 100f, 150f, 150f))
        assertEquals(CountPhase.Empty, CountState().phase)
        assertEquals(CountPhase.Marking, marking.phase)
        assertEquals(CountPhase.Ready, ready.phase)
        assertEquals(CountPhase.Counting, ready.copy(counting = true).phase)
        assertEquals(CountPhase.Counted, ready.copy(points = detected).phase)
    }

    private val area = Box(100f, 100f, 900f, 900f)
    private val counting =
        CountState(crop = area, exemplar = Box(450f, 450f, 550f, 550f)).countingStarted()

    private fun result(vararg points: Point) =
        CountResult(
            points.map { Detection(Box(it.x - 10, it.y - 10, it.x + 10, it.y + 10), 1f) },
            Heatmap(1, 1, floatArrayOf(0f), area),
        )

    @Test
    fun `counts in the crop as counting starts`() {
        assertEquals(area, counting.countedArea)
    }

    @Test
    fun `adjusts the crop while counting`() {
        val smaller = Box(200f, 200f, 800f, 800f)

        assertEquals(smaller, counting.cropped(smaller).crop)
    }

    @Test
    fun `keeps the crop inside the counted area once counting has started`() {
        val cropped = counting.cropped(Box(0f, 50f, 1000f, 800f))

        assertEquals(Box(100f, 100f, 900f, 800f), cropped.crop)
        assertEquals(area, cropped.withCount(result()).cropped(Box(0f, 0f, 1000f, 1000f)).crop)
    }

    @Test
    fun `grows the crop back up to the counted area`() {
        val grown = counting.cropped(Box(200f, 200f, 800f, 800f)).cropped(area)

        assertEquals(area, grown.crop)
    }

    @Test
    fun `counts only in the crop as the count arrives`() {
        val count =
            counting
                .cropped(Box(100f, 100f, 400f, 400f))
                .withCount(result(Point(200f, 200f), Point(700f, 700f)))

        assertEquals(listOf(Point(200f, 200f)), count.counted)
    }

    @Test
    fun `adjusts the crop freely again after clearing or a failed count`() {
        val whole = Box(0f, 0f, 1000f, 1000f)

        assertEquals(whole, counting.withCount(result()).cleared().cropped(whole).crop)
        assertEquals(whole, counting.countFailed().cropped(whole).crop)
    }
}
