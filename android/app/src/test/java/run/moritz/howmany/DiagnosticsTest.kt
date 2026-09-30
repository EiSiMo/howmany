package run.moritz.howmany

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ApplicationExitInfo.REASON_ANR
import android.app.ApplicationExitInfo.REASON_CRASH
import android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
import android.app.ApplicationExitInfo.REASON_LOW_MEMORY
import android.app.ApplicationExitInfo.REASON_SIGNALED
import android.app.ApplicationExitInfo.REASON_USER_REQUESTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsTest {
    private fun exit(timestamp: Long, reason: Int, importance: Int = IMPORTANCE_FOREGROUND) =
        ProcessExit(timestamp, reason, importance)

    @Test
    fun `finds the newest crash of any kind`() {
        for (reason in listOf(REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR, REASON_LOW_MEMORY)) {
            val crash = exit(3, reason)
            assertEquals(crash, lastCrash(listOf(crash, exit(2, REASON_CRASH)), seenUntil = 0))
        }
    }

    @Test
    fun `ignores crashes the user was already asked about`() {
        assertNull(lastCrash(listOf(exit(2, REASON_CRASH), exit(1, REASON_ANR)), seenUntil = 2))
    }

    @Test
    fun `ignores the app ending normally`() {
        val exits =
            listOf(
                exit(3, REASON_USER_REQUESTED),
                exit(2, REASON_SIGNALED),
                exit(1, REASON_LOW_MEMORY, IMPORTANCE_CACHED),
            )

        assertNull(lastCrash(exits, seenUntil = 0))
    }
}
