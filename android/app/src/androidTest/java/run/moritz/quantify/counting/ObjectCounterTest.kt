package run.moritz.quantify.counting

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.time.measureTimedValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectCounterTest {
    @Test
    fun countsLikeTheBenchmark() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model =
            instrumentation.targetContext.let { context ->
                File(context.cacheDir, MODEL_ASSET).also { file ->
                    context.assets.open(MODEL_ASSET).use { it.copyTo(file.outputStream()) }
                }
            }
        val image = instrumentation.context.assets.open("2147.jpg").use(BitmapFactory::decodeStream)
        // The benchmark's first exemplar for this image; prototype 4 finds 34 apples with it.
        val exemplars = listOf(Box(122f, 133f, 222f, 231f))

        ObjectCounter(model).use { counter ->
            val runs = List(4) { measureTimedValue { counter.detect(image, exemplars) } }
            runs.forEach { Log.i(TAG, "${it.value.size} objects in ${it.duration}") }

            assertEquals(34f, runs.last().value.size.toFloat(), 1f)
        }
    }

    private companion object {
        const val TAG = "ObjectCounterTest"
    }
}
