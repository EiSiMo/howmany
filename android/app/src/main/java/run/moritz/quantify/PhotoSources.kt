package run.moritz.quantify

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "PhotoSources"

/** The ways to get a photo: taking one with the camera app, or picking one from the gallery. */
class PhotoSources(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

/**
 * Takes photos with the system's camera app, which processes them better than a camera feed of our
 * own would, and picks them with the system's photo picker. Either hands the photo to [onPhoto];
 * cancelling hands over nothing. If there is no camera app, [onNoCamera] is called.
 */
@Composable
fun rememberPhotoSources(onPhoto: (Uri) -> Unit, onNoCamera: () -> Unit): PhotoSources {
    val context = LocalContext.current
    val photo by rememberUpdatedState(onPhoto)
    val noCamera by rememberUpdatedState(onNoCamera)
    val camera =
        rememberLauncherForActivityResult(TakePicture()) { taken ->
            if (taken) photo(takenPhoto(context))
        }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let(photo) }
    return remember(context) {
        PhotoSources(
            takePhoto = {
                try {
                    camera.launch(takenPhoto(context))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No camera app to take a photo", e)
                    noCamera()
                }
            },
            pickPhoto = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
        )
    }
}

/**
 * Where the camera app writes the photo it takes: always the same file in the cache, so it stays
 * out of the gallery, and the next photo replaces it. Being fixed, it survives the app being
 * recreated while the camera is open.
 */
private fun takenPhoto(context: Context): Uri {
    val file = File(context.cacheDir, "photos/photo.jpg").apply { parentFile?.mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}
