package run.moritz.howmany

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val viewModel: CountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        setContent {
            HowManyTheme { CountScreen(viewModel) }
        }
        // On recreation, the intent is the one already opened.
        if (savedInstanceState == null) open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /**
     * Saves the last export to the gallery if [intent] asks to; otherwise starts over with the
     * image it shares with or opens in the app, if any.
     */
    private fun open(intent: Intent) {
        if (intent.action == ACTION_SAVE_EXPORT) {
            save()
            return
        }
        val image =
            sharedImage(
                intent.action,
                intent.resolveType(this),
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
                intent.data,
            )
        // The intent grants reading the image while this activity lives, long enough to decode it.
        if (image != null) viewModel.pickPhoto(image)
    }

    private fun save() {
        lifecycleScope.launch {
            try {
                saveExport(this@MainActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cannot save the export", e)
                viewModel.exportFailed()
                return@launch
            }
            Toast.makeText(this@MainActivity, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The image an intent with [action] and MIME [type] shares with the app ([stream]) or opens in it
 * ([data]); null if it carries none, like when launched from the home screen.
 */
internal fun <U : Any> sharedImage(action: String?, type: String?, stream: U?, data: U?): U? =
    when {
        type?.startsWith("image/") != true -> null
        action == Intent.ACTION_SEND -> stream
        action == Intent.ACTION_VIEW -> data
        else -> null
    }
