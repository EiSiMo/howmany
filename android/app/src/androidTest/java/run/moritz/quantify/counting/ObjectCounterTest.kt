package run.moritz.quantify.counting

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.time.measureTimedValue
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectCounterTest {
    // The benchmark's first exemplar for each image and prototype 4's count with it.

    /** Apples scaled down to a 416 x 320 model input: the typical case. */
    @Test
    fun countsApplesLikeTheBenchmark() =
        countsLikeTheBenchmark("2147.jpg", Box(122f, 133f, 222f, 231f), 36)

    /**
     * Small marbles filling the whole 1024 x 1024 model input: the worst case for time and memory.
     */
    @Test
    fun countsMarblesLikeTheBenchmark() =
        countsLikeTheBenchmark("5574.jpg", Box(280f, 218f, 301f, 240f), 93)

    private fun countsLikeTheBenchmark(name: String, exemplar: Box, expected: Int) {
        val image = instrumentation.context.assets.open(name).use(BitmapFactory::decodeStream)
        val runs = List(4) { measureTimedValue { counter.detect(image, listOf(exemplar)) } }
        runs.forEach { Log.i(TAG, "$name: ${it.value.size} objects in ${it.duration}") }
        Log.i(TAG, "$name: peak memory so far ${peakMemoryMb()} MB")

        assertEquals(expected.toFloat(), runs.last().value.size.toFloat(), expected * 0.05f)
    }

    companion object {
        private const val TAG = "ObjectCounterTest"
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val counter by lazy {
            val context = instrumentation.targetContext
            val model =
                File(context.cacheDir, MODEL_ASSET).also { file ->
                    context.assets.open(MODEL_ASSET).use { it.copyTo(file.outputStream()) }
                }
            ObjectCounter(model)
        }

        /** The process's peak resident memory, as Android's low-memory killer sees it. */
        private fun peakMemoryMb(): Long =
            File("/proc/self/status")
                .readLines()
                .first { it.startsWith("VmHWM:") }
                .split(Regex("\\s+"))[1]
                .toLong() / 1024

        @JvmStatic @AfterClass fun closeCounter() = counter.close()
    }
}
