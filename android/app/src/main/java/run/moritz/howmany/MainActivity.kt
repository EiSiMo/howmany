package run.moritz.howmany

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    private val viewModel: CountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark and edge to edge, like a camera: the photo is the hero, and the system's
        // accent color ties the controls to the counted points.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current)) {
                CountScreen(viewModel)
            }
        }
        // On recreation, the intent is the one already opened.
        if (savedInstanceState == null) open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /** Starts over with the image [intent] shares with or opens in the app, if any. */
    private fun open(intent: Intent) {
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
