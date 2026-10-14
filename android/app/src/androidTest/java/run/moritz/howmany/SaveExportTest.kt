package run.moritz.howmany

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saving an export to the gallery from the share sheet happens in the open app: a second one would
 * load a second model, and Android would end the app for the memory.
 */
@RunWith(AndroidJUnit4::class)
class SaveExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun finishActivities() {
        instrumentation.runOnMainSync { liveActivities().forEach(Activity::finish) }
    }

    @Test
    fun savesInTheOpenApp() {
        val app =
            instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        // In place of the share sheet, which opens on top of the app.
        instrumentation.runOnMainSync {
            app.startActivity(Intent(app, AboutActivity::class.java))
        }
        waitUntil { resumed() is AboutActivity }

        PendingIntent.getActivity(
                context,
                0,
                saveExportIntent(context),
                PendingIntent.FLAG_IMMUTABLE,
            )
            .send()
        waitUntil { resumed() is MainActivity }

        var apps = emptyList<Activity>()
        instrumentation.runOnMainSync { apps = liveActivities().filterIsInstance<MainActivity>() }
        assertEquals(listOf(app), apps)
    }

    private fun resumed(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync {
            activity =
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull()
        }
        return activity
    }

    /** Must run on the main thread. */
    private fun liveActivities(): List<Activity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return LIVE_STAGES.flatMap { monitor.getActivitiesInStage(it) }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(100)
        }
    }

    private companion object {
        val LIVE_STAGES =
            listOf(
                Stage.PRE_ON_CREATE,
                Stage.CREATED,
                Stage.STARTED,
                Stage.RESUMED,
                Stage.PAUSED,
                Stage.STOPPED,
                Stage.RESTARTED,
            )
    }
}
