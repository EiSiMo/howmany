package run.moritz.howmany

import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.CountResult
import run.moritz.howmany.counting.Detection
import run.moritz.howmany.counting.Heatmap

/**
 * What the app keeps for Android to pass on as it goes to the background stays small: beyond about
 * a megabyte for all apps at once, Android crashes the app.
 */
@RunWith(AndroidJUnit4::class)
class SavedCountSizeTest {
    @Test
    fun keepsALargeCountSmall() {
        val area = Box(0f, 0f, 2048f, 2048f)
        val detections = List(2000) { Detection(Box(it.toFloat(), 0f, it + 1f, 1f), 0.5f) }
        // As large as the one of a 1024x768 input, which crashed the app.
        val heatmap = Heatmap(384, 512, FloatArray(384 * 512), area)
        val count =
            CountState(crop = area, exemplar = Box(0f, 0f, 10f, 10f))
                .countingStarted()
                .withCount(CountResult(detections, heatmap))
        val handle = SavedStateHandle()

        handle.saveCount(count)
        val parcel = Parcel.obtain()
        val bytes =
            try {
                parcel.writeBundle(handle.savedStateProvider().saveState())
                parcel.dataSize()
            } finally {
                parcel.recycle()
            }

        assertTrue("$bytes bytes", bytes < 100_000)
    }
}
