package run.moritz.howmany

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app shows the license of every work it bundles, which their licenses require. */
@RunWith(AndroidJUnit4::class)
class LicensesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun includesTheModelTheRuntimeAndTheFontWithTheirLicenseTexts() {
        val libraries = openSourceLibraries(context)

        for (name in listOf("GeCo2", "SAM 2", "ONNX Runtime", "Space Grotesk")) {
            val library = libraries.find { it.name.startsWith(name) }
            assertTrue("$name missing", library != null)
            assertTrue(
                "$name without license text",
                library!!.licenses.any { !it.licenseContent.isNullOrBlank() },
            )
        }
    }
}
