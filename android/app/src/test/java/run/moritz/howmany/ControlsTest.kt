package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Test
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Point

class ControlsTest {
    private val marking = CountState(crop = Box(0f, 0f, 1000f, 1000f))
    private val ready = marking.copy(exemplars = listOf(Box(10f, 10f, 50f, 50f)))
    private val full = marking.copy(exemplars = List(MAX_EXEMPLARS) { Box(0f, 0f, 10f, 10f) })
    private val counted = ready.copy(points = listOf(Point(100f, 100f)))

    @Test
    fun `hints at what to do next and how many exemplars are marked`() {
        assertEquals(CountHint(R.string.empty_text), hint(CountState()))
        assertEquals(CountHint(R.string.mark_first_exemplar, 0), hint(marking))
        assertEquals(CountHint(R.string.mark_more_exemplars, 1), hint(ready))
        assertEquals(CountHint(R.string.adjust_crop, MAX_EXEMPLARS), hint(full))
        assertEquals(CountHint(R.string.counting), hint(ready.copy(counting = true)))
        assertEquals(CountHint(R.string.correct), hint(counted))
    }

    @Test
    fun `tells what went wrong instead`() {
        assertEquals(
            CountHint(R.string.error_count_failed),
            hint(ready.copy(error = CountError.CountFailed)),
        )
        assertEquals(
            CountHint(R.string.error_photo_unreadable),
            hint(CountState(error = CountError.PhotoUnreadable)),
        )
        assertEquals(
            CountHint(R.string.error_no_camera),
            hint(CountState(error = CountError.NoCamera)),
        )
        assertEquals(
            CountHint(R.string.error_export_failed),
            hint(counted.copy(error = CountError.ExportFailed)),
        )
    }
}
