package run.moritz.howmany

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
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
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import java.io.File

private const val TAG = "PhotoSources"
private const val CAMERA_PREFERENCES = "camera"
private const val CHOSEN_CAMERA = "chosen"

/**
 * The ways to get a photo: taking one with a camera app, or picking one from the gallery. Long
 * pressing [takePhoto] means [chooseCamera]: the user chooses the camera app again.
 */
class PhotoSources(
    val takePhoto: () -> Unit,
    val chooseCamera: () -> Unit,
    val pickPhoto: () -> Unit,
)

/**
 * Takes photos with a camera app, which processes them better than a camera feed of our own would,
 * and picks them with the system's photo picker. Either hands the photo to [onPhoto]; cancelling
 * hands over nothing. If there is no camera app, [onNoCamera] is called.
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
        val takePhoto = { choose: Boolean ->
            try {
                camera.takePhoto(context, takenPhoto(context), choose)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No camera app to take a photo", e)
                noCamera()
            }
        }
        PhotoSources(
            takePhoto = { takePhoto(false) },
            chooseCamera = { takePhoto(true) },
            pickPhoto = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
        )
    }
}

/** Which camera app takes a photo. Apps are named by component, as in "package/activity". */
sealed interface CameraChoice {
    /** The preinstalled camera app, or the one Android lets the user choose among those. */
    data object Preinstalled : CameraChoice

    /** The camera app [app]. */
    data class App(val app: String) : CameraChoice

    /** The camera app the user chooses among [apps], which we remember. */
    data class Choose(val apps: List<String>) : CameraChoice

    /** None, as there is no camera app. */
    data object None : CameraChoice
}

/**
 * Chooses which of the camera [apps] takes a photo. The one the user chose before, [remembered],
 * does while it is there; else Android's choice if there is a [preinstalled] camera app, which
 * since Android 11 only those can be, else the user's. Asked to, the user [choose]s again.
 */
fun chooseCamera(
    apps: List<String>,
    preinstalled: Boolean,
    remembered: String?,
    choose: Boolean = false,
): CameraChoice =
    when {
        choose && apps.size > 1 -> CameraChoice.Choose(apps)
        remembered != null && remembered in apps -> CameraChoice.App(remembered)
        preinstalled -> CameraChoice.Preinstalled
        apps.isEmpty() -> CameraChoice.None
        apps.size == 1 -> CameraChoice.App(apps.single())
        else -> CameraChoice.Choose(apps)
    }

/**
 * Asks a camera app to take a photo into [output], as [chooseCamera] chooses, with the user
 * choosing again if [choose]. Throws [ActivityNotFoundException] if no camera app can take one.
 */
// Seeing the camera apps, as the manifest's queries let us, is all we need.
@SuppressLint("QueryPermissionsNeeded")
private fun ActivityResultLauncher<Intent>.takePhoto(
    context: Context,
    output: Uri,
    choose: Boolean,
) {
    val request =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, output)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            .apply { clipData = ClipData.newRawUri(null, output) }
    val packages = context.packageManager
    // Android hides camera apps the user installed from a request that names no app, also when
    // asked which apps answer it, so we ask each app we see.
    val apps =
        packages
            .getInstalledPackages(0)
            .flatMap {
                packages.queryIntentActivities(Intent(request).setPackage(it.packageName), 0)
            }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    val preinstalled = packages.queryIntentActivities(request, 0).isNotEmpty()
    val remembered = rememberedCamera(context)
    if (remembered != null && apps.none { it.flattenToString() == remembered }) {
        Log.i(TAG, "Forgetting the chosen camera app $remembered, which is gone")
        rememberCamera(context, null)
    }
    val choice = chooseCamera(apps.map { it.flattenToString() }, preinstalled, remembered, choose)
    Log.i(TAG, "Found ${apps.size} camera apps, preinstalled: $preinstalled, taking $choice")
    val explicit = { app: String ->
        Intent(request).setComponent(ComponentName.unflattenFromString(app))
    }
    when (choice) {
        CameraChoice.Preinstalled -> launch(request)
        is CameraChoice.App -> launch(explicit(choice.app))
        is CameraChoice.Choose -> {
            val intents = choice.apps.map(explicit)
            // Android tells CameraChosen which one the user chose.
            val chosen =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, CameraChosen::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            launch(
                Intent.createChooser(
                        intents.first(),
                        context.getString(R.string.take_photo_with),
                        chosen.intentSender,
                    )
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
            )
        }
        CameraChoice.None -> throw ActivityNotFoundException("No camera app can take a photo")
    }
}

/** Remembers the camera app the user chose, which Android tells after the choice. */
class CameraChosen : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app =
            IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_CHOSEN_COMPONENT,
                ComponentName::class.java,
            )
        Log.i(TAG, "The user chose the camera app $app")
        app?.let { rememberCamera(context, it.flattenToString()) }
    }
}

private fun cameraPreferences(context: Context) =
    context.getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)

private fun rememberedCamera(context: Context): String? =
    cameraPreferences(context).getString(CHOSEN_CAMERA, null)

private fun rememberCamera(context: Context, app: String?) =
    cameraPreferences(context).edit { putString(CHOSEN_CAMERA, app) }

/**
 * Where the camera app writes the photo it takes: always the same file in the cache, so it stays
 * out of the gallery, and the next photo replaces it. Being fixed, it survives the app being
 * recreated while the camera is open.
 */
private fun takenPhoto(context: Context): Uri {
    val file = File(context.cacheDir, "photos/photo.jpg").apply { parentFile?.mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}
