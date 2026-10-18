package run.moritz.howmany.counting

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.time.measureTimedValue
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectCounterTest {
    // The benchmark's first exemplar for each image and model/counter.py's count with it.

    /** Apples scaled down to a 416 x 320 model input: the typical case. */
    @Test
    fun countsApplesLikeTheBenchmark() =
        countsLikeTheBenchmark("2147.jpg", Box(122f, 133f, 222f, 231f), 34)

    /**
     * Small marbles filling the whole 1024 x 1024 model input: the worst case for time and memory.
     */
    @Test
    fun countsMarblesLikeTheBenchmark() =
        countsLikeTheBenchmark("5574.jpg", Box(280f, 218f, 301f, 240f), 93)

    /** Leaving the app while it counts closes the counter; the count still finishes. */
    @Test
    fun closingWhileCountingLetsTheCountFinish() {
        val image = instrumentation.context.assets.open("5574.jpg").use(BitmapFactory::decodeStream)
        val closing = ObjectCounter.fromAssets(instrumentation.targetContext)
        val count = CompletableFuture.supplyAsync {
            closing.detect(image, listOf(Box(280f, 218f, 301f, 240f))).detections.size
        }
        // Long enough for the count to start, far shorter than it takes.
        Thread.sleep(500)

        closing.close()

        assertFalse(count.isDone)
        assertEquals(93f, count.get().toFloat(), 93 * 0.05f)
    }

    /**
     * Counting gives back the memory it needed, as Android kills apps holding much of it, even in
     * the foreground.
     */
    @Test
    fun countingGivesBackItsMemory() {
        val image = instrumentation.context.assets.open("5574.jpg").use(BitmapFactory::decodeStream)
        // Its own counter, as the others may already have kept memory from earlier counts.
        val (before, after) =
            ObjectCounter.fromAssets(instrumentation.targetContext).use {
                val before = memoryMb("VmRSS")
                it.detect(image, listOf(Box(280f, 218f, 301f, 240f)))
                before to memoryMb("VmRSS")
            }
        Log.i(TAG, "Resident memory $before MB before counting, $after MB after")
        assertTrue("Counting kept ${after - before} MB", after - before < 100)
    }

    private fun countsLikeTheBenchmark(name: String, exemplar: Box, expected: Int) {
        val image = instrumentation.context.assets.open(name).use(BitmapFactory::decodeStream)
        val runs =
            List(4) { measureTimedValue { counter.detect(image, listOf(exemplar)).detections } }
        runs.forEach { Log.i(TAG, "$name: ${it.value.size} objects in ${it.duration}") }
        Log.i(TAG, "$name: peak memory so far ${memoryMb("VmHWM")} MB")

        assertEquals(expected.toFloat(), runs.last().value.size.toFloat(), expected * 0.05f)
    }

    companion object {
        private const val TAG = "ObjectCounterTest"
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private lateinit var counter: ObjectCounter

        /** Creates the counter before any timed run, so the runs are comparable. */
        @JvmStatic
        @BeforeClass
        fun createCounter() {
            val (created, duration) =
                measureTimedValue { ObjectCounter.fromAssets(instrumentation.targetContext) }
            counter = created
            Log.i(TAG, "Counter created in $duration")
        }

        /**
         * The process's resident memory, as Android's low-memory killer sees it: now ("VmRSS") or
         * at its peak ("VmHWM").
         */
        private fun memoryMb(field: String): Long =
            File("/proc/self/status")
                .readLines()
                .first { it.startsWith("$field:") }
                .split(Regex("\\s+"))[1]
                .toLong() / 1024

        @JvmStatic @AfterClass fun closeCounter() = counter.close()
    }
}
