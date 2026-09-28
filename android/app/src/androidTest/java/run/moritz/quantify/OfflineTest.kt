package run.moritz.quantify

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The app works fully offline, so no dependency may sneak network access or telemetry in. */
@RunWith(AndroidJUnit4::class)
class OfflineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val packageInfo =
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS).toLong()
            ),
        )

    @Test
    fun cannotAccessTheNetwork() {
        val permissions = packageInfo.requestedPermissions.orEmpty().toList()

        assertEquals(
            emptyList<String>(),
            permissions.filter { it.contains("NETWORK") || it.endsWith("INTERNET") },
        )
    }

    @Test
    fun startsNoTelemetry() {
        val providers = packageInfo.providers.orEmpty().map { it.name }

        assertEquals(emptyList<String>(), providers.filter { it.contains("Telemetry") })
    }
}
