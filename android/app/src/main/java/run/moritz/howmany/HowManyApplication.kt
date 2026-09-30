package run.moritz.howmany

import android.app.Application
import android.util.Log

private const val TAG = "HowManyApplication"

class HowManyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        Log.i(TAG, "Starting ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
