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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import java.io.File

private const val TAG = "PhotoSources"
private const val CAMERA_PREFERENCES = "camera"
private const val CHOSEN_CAMERA = "chosen"
private const val KNOWN_CAMERAS = "known"
private val SHEET_PADDING = 24.dp
private val CAMERA_APP_PADDING = 12.dp
private val CAMERA_APP_GAP = 16.dp
private val CAMERA_APP_ICON = 40.dp

/** A camera app the user can choose, with its [name] and [icon] as the launcher shows them. */
class CameraApp(val component: ComponentName, val name: String, val icon: ImageBitmap)

/**
 * The ways to get a photo: taking one with a camera app, or picking one from the gallery. Long
 * pressing [takePhoto] means [chooseCamera]: the user chooses the camera app again. While the user
 * chooses, [choosing] holds the camera apps to choose among, which [CameraSheet] shows; the user
 * then [takePhotoWith] one, or [cancelChoice]s.
 */
class PhotoSources(
    val takePhoto: () -> Unit,
    val chooseCamera: () -> Unit,
    val pickPhoto: () -> Unit,
    val choosing: State<List<CameraApp>?>,
    val takePhotoWith: (CameraApp) -> Unit,
    val cancelChoice: () -> Unit,
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
        val choosing = mutableStateOf<List<CameraApp>?>(null)
        // Without a component, Android picks among the preinstalled camera apps.
        val launch = { app: ComponentName? ->
            try {
                camera.launch(photoRequest(takenPhoto(context)).setComponent(app))
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No camera app to take a photo", e)
                noCamera()
            }
        }
        val takePhoto = { choose: Boolean ->
            when (val choice = chooseCamera(context, choose)) {
                CameraChoice.Preinstalled -> launch(null)
                is CameraChoice.App -> launch(ComponentName.unflattenFromString(choice.app))
                is CameraChoice.Choose ->
                    choosing.value = choice.apps.map { cameraApp(context, it) }
                CameraChoice.None -> noCamera()
            }
        }
        PhotoSources(
            takePhoto = { takePhoto(false) },
            chooseCamera = { takePhoto(true) },
            pickPhoto = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            choosing = choosing,
            takePhotoWith = { app ->
                Log.i(TAG, "The user chose the camera app ${app.component.flattenToString()}")
                choosing.value = null
                cameraPreferences(context).edit {
                    putString(CHOSEN_CAMERA, app.component.flattenToString())
                }
                launch(app.component)
            },
            cancelChoice = {
                Log.i(TAG, "The user chose no camera app")
                choosing.value = null
            },
        )
    }
}

/** A sheet for the user to choose the camera app among those [sources] offers, while it does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSheet(sources: PhotoSources) {
    val apps = sources.choosing.value ?: return
    ModalBottomSheet(onDismissRequest = sources.cancelChoice) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(bottom = SHEET_PADDING)
                .navigationBarsPadding()
        ) {
            Text(
                stringResource(R.string.take_photo_with),
                Modifier.padding(horizontal = SHEET_PADDING),
                style = MaterialTheme.typography.headlineSmall,
            )
            for (app in apps) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { sources.takePhotoWith(app) }
                        .padding(horizontal = SHEET_PADDING, vertical = CAMERA_APP_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(CAMERA_APP_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(app.icon, null, Modifier.size(CAMERA_APP_ICON))
                    Text(app.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
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
 * Chooses which of the camera [apps] takes a photo: the only one, or the one the user chose before,
 * [remembered], while it is there. Else the user chooses, also when asked to [choose] again or when
 * there are apps that were not among those [known] before. Without any app we see, a [preinstalled]
 * one may still take it.
 */
fun chooseCamera(
    apps: List<String>,
    preinstalled: Boolean,
    remembered: String?,
    known: Set<String>? = null,
    choose: Boolean = false,
): CameraChoice {
    val added = known != null && apps.any { it !in known }
    return when {
        apps.size > 1 && (choose || added || remembered !in apps) -> CameraChoice.Choose(apps)
        remembered != null && remembered in apps -> CameraChoice.App(remembered)
        apps.size == 1 -> CameraChoice.App(apps.single())
        preinstalled -> CameraChoice.Preinstalled
        else -> CameraChoice.None
    }
}

/**
 * Chooses the camera app as [chooseCamera] does, with the user choosing again if [choose], and
 * remembers which camera apps there are.
 */
// Seeing the camera apps, as the manifest's queries let us, is all we need.
@SuppressLint("QueryPermissionsNeeded")
private fun chooseCamera(context: Context, choose: Boolean): CameraChoice {
    val request = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    val packages = context.packageManager
    // Since Android 11 only preinstalled camera apps answer a request that names no app, and
    // Android hides the others even when asked which apps answer it, so we ask each app we see.
    val apps =
        packages
            .getInstalledPackages(0)
            .flatMap {
                packages.queryIntentActivities(Intent(request).setPackage(it.packageName), 0)
            }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .map { it.flattenToString() }
    val preinstalled = packages.queryIntentActivities(request, 0).isNotEmpty()
    val preferences = cameraPreferences(context)
    val remembered = preferences.getString(CHOSEN_CAMERA, null)
    val known = preferences.getStringSet(KNOWN_CAMERAS, null)
    val choice = chooseCamera(apps, preinstalled, remembered, known, choose)
    preferences.edit {
        if (remembered != null && remembered !in apps) {
            Log.i(TAG, "Forgetting the chosen camera app $remembered, which is gone")
            remove(CHOSEN_CAMERA)
        }
        putStringSet(KNOWN_CAMERAS, apps.toSet())
    }
    Log.i(TAG, "Found camera apps $apps, preinstalled: $preinstalled, taking $choice")
    return choice
}

/** The camera [app], named by component, with its name and icon. */
private fun cameraApp(context: Context, app: String): CameraApp {
    val component = checkNotNull(ComponentName.unflattenFromString(app)) { "No component: $app" }
    val packages = context.packageManager
    return CameraApp(
        component,
        packages.getActivityInfo(component, 0).loadLabel(packages).toString(),
        packages.getActivityIcon(component).toBitmap().asImageBitmap(),
    )
}

/** Asks a camera app to take a photo into [output]. */
private fun photoRequest(output: Uri): Intent =
    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        .putExtra(MediaStore.EXTRA_OUTPUT, output)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(null, output) }

private fun cameraPreferences(context: Context) =
    context.getSharedPreferences(CAMERA_PREFERENCES, Context.MODE_PRIVATE)

/**
 * Where the camera app writes the photo it takes: always the same file in the cache, so it stays
 * out of the gallery, and the next photo replaces it. Being fixed, it survives the app being
 * recreated while the camera is open.
 */
private fun takenPhoto(context: Context): Uri {
    val file = File(context.cacheDir, "photos/photo.jpg").apply { parentFile?.mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
}
