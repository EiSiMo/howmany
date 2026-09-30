package run.moritz.howmany

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * After the app crashed, asks the user once to send us a report about it. Until they answer, it
 * asks again whenever the app starts.
 */
@Composable
fun CrashPrompt() {
    val context = LocalContext.current
    // Outlives the dialog, so sending goes on after it closes.
    val scope = rememberCoroutineScope()
    var crash by remember { mutableStateOf<Crash?>(null) }
    LaunchedEffect(Unit) {
        crash = withContext(Dispatchers.IO) { Diagnostics.unseenCrash(context) }
    }
    val shown = crash ?: return
    fun answer(send: Boolean) {
        Diagnostics.seen(context, shown)
        crash = null
        if (send) scope.launch { Diagnostics.send(context, shown) }
    }
    AlertDialog(
        onDismissRequest = { answer(send = false) },
        title = { Text(stringResource(R.string.crash_title)) },
        text = { Text(stringResource(R.string.crash_text)) },
        confirmButton = {
            TextButton(onClick = { answer(send = true) }) {
                Text(stringResource(R.string.send_report))
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(send = false) }) {
                Text(stringResource(R.string.not_now))
            }
        },
    )
}
