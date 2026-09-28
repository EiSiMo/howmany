package run.moritz.howmany

import org.junit.Assert.assertEquals
import org.junit.Test
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Point

class ControlsTest {
    private val marking = CountState(crop = Box(0f, 0f, 1000f, 1000f))
    private val ready = marking.copy(exemplar = Box(10f, 10f, 50f, 50f))
    private val counted = ready.copy(points = listOf(Point(100f, 100f)))

    @Test
    fun `hints at what to do next`() {
        assertEquals(R.string.empty_text, hint(CountState()))
        assertEquals(R.string.mark_exemplar, hint(marking))
        assertEquals(R.string.adjust_crop, hint(ready))
        assertEquals(R.string.counting, hint(ready.copy(counting = true)))
        assertEquals(R.string.correct, hint(counted))
    }

    @Test
    fun `tells what went wrong instead`() {
        assertEquals(
            R.string.error_count_failed,
            hint(ready.copy(error = CountError.CountFailed)),
        )
        assertEquals(
            R.string.error_photo_unreadable,
            hint(CountState(error = CountError.PhotoUnreadable)),
        )
        assertEquals(R.string.error_no_camera, hint(CountState(error = CountError.NoCamera)))
    }
}
