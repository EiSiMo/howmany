package run.moritz.howmany

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A report holds what we need to understand a problem, read from the phone. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsReportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun holdsTheVersionAndTheAppsLog() {
        val marker = "marker ${UUID.randomUUID()}"
        Log.i("DiagnosticsReportTest", marker)

        // The log reaches its file a moment later.
        val deadline = System.currentTimeMillis() + 5000
        var report = Diagnostics.report(context, crash = null)
        while (marker !in report && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            report = Diagnostics.report(context, crash = null)
        }

        assertTrue(report, report.startsWith("how many? ${BuildConfig.VERSION_NAME}"))
        assertTrue(report, marker in report)
    }
}
