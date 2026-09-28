package run.moritz.howmany

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.util.withContext

private val SHEET_PADDING = 24.dp
private val LICENSE_GAP = 20.dp

/**
 * The open source works the app bundles, by name, with their licenses: its dependencies as
 * AboutLibraries collects them at build time, and the model and font defined in config/.
 */
fun openSourceLibraries(context: Context): List<Library> =
    Libs.Builder().withContext(context).build().libraries.sortedBy { it.name.lowercase() }

/** The [library]'s version and the names of its licenses, in a line. */
fun licenseSummary(library: Library): String =
    listOfNotNull(
            library.artifactVersion,
            library.licenses.map { it.name }.distinct().joinToString().ifEmpty { null },
        )
        .joinToString(" · ")

/** A sheet with the full texts of the [library]'s licenses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseSheet(library: Library, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_PADDING)
                .padding(bottom = SHEET_PADDING)
                .navigationBarsPadding()
        ) {
            Text(library.name, style = MaterialTheme.typography.headlineSmall)
            val authors = library.developers.mapNotNull { it.name }.joinToString()
            Text(
                listOf(authors, licenseSummary(library))
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A library's own license with its copyright comes first, before a generic one of
            // the same kind that its build declares.
            for (license in library.licenses.distinctBy { it.spdxId ?: it.hash }) {
                Spacer(Modifier.height(LICENSE_GAP))
                Text(
                    license.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    license.licenseContent ?: license.url.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
