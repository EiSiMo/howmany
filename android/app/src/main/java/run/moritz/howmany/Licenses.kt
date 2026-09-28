package run.moritz.howmany

import android.content.Context
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withContext

/**
 * The open source works the app bundles, by name, with their licenses: its dependencies as
 * AboutLibraries collects them at build time, and the model and font defined in config/.
 */
fun openSourceLibraries(context: Context): List<Library> =
    Libs.Builder().withContext(context).build().libraries.sortedBy { it.name.lowercase() }
