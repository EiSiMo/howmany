package run.moritz.howmany

import android.app.Application
import android.util.Log
import kotlin.time.measureTimedValue
import run.moritz.howmany.counting.ObjectCounter

private const val TAG = "HowManyApplication"

class HowManyApplication : Application() {
    /**
     * The counter, one for all screens counting at once: each would load the model, too much memory
     * for some phones. Prepared (copying the model out of the APK and loading it, seconds on a
     * phone) in the background as soon as the first takes it, while the user picks a photo and
     * marks an exemplar. It holds only the model weights until the first count.
     */
    val objectCounter = Shared {
        val (counter, duration) =
            try {
                measureTimedValue { ObjectCounter.fromAssets(this) }
            } catch (e: Exception) {
                // Surfaces only once the user counts, which may be never.
                Log.e(TAG, "Cannot prepare the counter", e)
                throw e
            }
        Log.i(TAG, "Counter ready in $duration")
        counter
    }

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        Log.i(TAG, "Starting ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    // Android warns of running low on memory before it kills apps, though since Android 14 only
    // once the app is out of sight.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "Asked to free memory (level $level). ${Diagnostics.memory(this)}")
    }
}
