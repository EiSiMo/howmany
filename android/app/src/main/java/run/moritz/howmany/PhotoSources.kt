package run.moritz.howmany

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
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
        rememberLauncherForActivityResult(StartActivityForResult()) { result ->
            val taken = result.resultCode == Activity.RESULT_OK
            Log.i(TAG, if (taken) "Took a photo" else "Took no photo")
            if (taken) photo(takenPhoto(context))
        }
    val picker =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            Log.i(TAG, if (uri != null) "Picked a photo" else "Picked no photo")
            uri?.let(photo)
        }
    return remember(context) {
        PhotoSources(
            takePhoto = {
                try {
                    camera.takePhoto(context, takenPhoto(context))
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
 * Asks a camera app to take a photo into [output]. Since Android 11 only preinstalled camera apps
 * answer a request that names none, so without one, like when the user removed it, we ask the
 * camera apps they installed by name, letting them choose if there are several. Throws
 * [ActivityNotFoundException] if no camera app can take a photo.
 */
// Seeing the camera apps, as the manifest's queries let us, is all we need.
@SuppressLint("QueryPermissionsNeeded")
private fun ActivityResultLauncher<Intent>.takePhoto(context: Context, output: Uri) {
    val request =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, output)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            .apply { clipData = ClipData.newRawUri(null, output) }
    val packages = context.packageManager
    if (packages.queryIntentActivities(request, 0).isNotEmpty()) {
        launch(request)
        return
    }
    // Android hides installed camera apps from a request that names no app, also when asked which
    // apps answer it, so we ask each app we see.
    val apps =
        packages
            .getInstalledPackages(0)
            .flatMap {
                packages.queryIntentActivities(Intent(request).setPackage(it.packageName), 0)
            }
            .map {
                Intent(request)
                    .setComponent(ComponentName(it.activityInfo.packageName, it.activityInfo.name))
            }
    Log.i(TAG, "No preinstalled camera app, found ${apps.size} installed ones")
    when (apps.size) {
        0 -> throw ActivityNotFoundException("No camera app can take a photo")
        1 -> launch(apps.single())
        else ->
            launch(
                Intent.createChooser(apps.first(), context.getString(R.string.take_photo_with))
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, apps.drop(1).toTypedArray())
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
