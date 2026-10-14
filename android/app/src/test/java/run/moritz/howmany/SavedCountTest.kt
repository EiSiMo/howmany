package run.moritz.howmany

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.CountResult
import run.moritz.howmany.counting.Detection
import run.moritz.howmany.counting.Heatmap

class SavedCountTest {
    private val area = Box(100f, 100f, 900f, 900f)
    private val exemplar = Box(450f, 450f, 550f, 550f)
    private val counting = CountState(crop = area, exemplar = exemplar).countingStarted()

    @Test
    fun `restores a corrected count`() {
        val result =
            CountResult(
                listOf(
                    Detection(Box(190f, 190f, 210f, 210f), 1f),
                    Detection(Box(690f, 690f, 710f, 710f), 0f),
                ),
                Heatmap(1, 2, floatArrayOf(0.25f, 0.75f), area),
            )
        val count = counting.withCount(result).cropped(Box(100f, 100f, 800f, 800f))
        val handle = SavedStateHandle()

        handle.saveCount(count)
        val restored = handle.restoredCount()!!

        assertEquals(count.crop, restored.crop)
        assertEquals(count.countedArea, restored.countedArea)
        assertEquals(count.exemplar, restored.exemplar)
        assertEquals(count.points, restored.points)
        assertEquals(count.uncertain, restored.uncertain)
        assertEquals(CountPhase.Counted, restored.phase)
    }

    @Test
    fun `keeps no heatmap, too large for what Android keeps`() {
        val count =
            counting.withCount(
                CountResult(emptyList(), Heatmap(256, 192, FloatArray(256 * 192), area))
            )
        val handle = SavedStateHandle()

        handle.saveCount(count)

        assertNull(handle.restoredCount()!!.heatmap)
    }

    @Test
    fun `restores an interrupted count as ready to count again`() {
        val handle = SavedStateHandle()

        handle.saveCount(counting)
        val restored = handle.restoredCount()!!

        assertEquals(CountPhase.Ready, restored.phase)
        assertNull(restored.countedArea)
    }

    @Test
    fun `restores nothing without a photo`() {
        val handle = SavedStateHandle()

        handle.saveCount(counting)
        handle.saveCount(CountState())

        assertNull(handle.restoredCount())
    }
}
