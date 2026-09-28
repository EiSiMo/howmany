package run.moritz.howmany

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.service.chooser.ChooserAction
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.moritz.howmany.counting.Box as ImageBox
import run.moritz.howmany.counting.Point

private const val TAG = "Export"
/** What starts the app to save the last export to the gallery, from the share sheet. */
const val ACTION_SAVE_EXPORT = "run.moritz.howmany.action.SAVE_EXPORT"
// Small crops are enlarged to this many pixels on their longer side, so the numbers stay legible.
private const val MIN_EXPORT_SIZE = 1080f
// Points are as large relative to the export as on a phone showing it this many dp wide.
private const val EXPORT_WIDTH_DP = 400f
private const val JPEG_QUALITY = 90
private const val MIME_TYPE = "image/jpeg"
private val GALLERY_FOLDER = "${Environment.DIRECTORY_PICTURES}/how many"

/**
 * Exports a counted photo: the crop with its numbered points, the uncertain ones highlighted, as
 * the app shows them. Opens the system's share sheet with it, which from Android 14 on also offers
 * to save it to the gallery (see [saveExport]). If it cannot be exported, [onFailed] is called.
 */
@Composable
fun rememberExport(onFailed: () -> Unit): (CountState) -> Unit {
    val context = LocalContext.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val locale = LocalConfiguration.current.locales[0]
    val pointColor = MaterialTheme.colorScheme.primary
    val failed by rememberUpdatedState(onFailed)
    val scope = rememberCoroutineScope()
    return export@{ state ->
        val photo = state.photo ?: return@export
        val crop = state.crop ?: return@export
        val points = state.counted ?: return@export
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val image =
                        render(photo, crop, points, state.uncertain, pointColor) { density ->
                            PointNumbers(
                                TextMeasurer(fontFamilyResolver, density, LayoutDirection.Ltr),
                                locale,
                            )
                        }
                    withContext(Dispatchers.IO) {
                        exportFile(context).outputStream().use {
                            check(image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it))
                        }
                    }
                }
                context.startActivity(shareSheet(context))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cannot export the count", e)
                failed()
            }
        }
    }
}

/** Saves the last export to the gallery, in a folder of its own. */
suspend fun saveExport(context: Context) =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "how-many-${System.currentTimeMillis()}")
                put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_FOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values)) { "Cannot create an image" }
        try {
            val output = checkNotNull(resolver.openOutputStream(uri)) { "Cannot write $uri" }
            output.use { exportFile(context).inputStream().use { input -> input.copyTo(it) } }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        Log.i(TAG, "Saved the export to $uri")
    }

/**
 * The [crop] of [photo] with [points] drawn on it, enlarged if small; the points keep their size
 * relative to the image, their numbers measured by the [numbers] for a density.
 */
private fun render(
    photo: Bitmap,
    crop: ImageBox,
    points: List<Point>,
    uncertain: Set<Point>,
    pointColor: Color,
    numbers: (Density) -> PointNumbers,
): Bitmap {
    val scale = max(1f, MIN_EXPORT_SIZE / max(crop.width, crop.height))
    val size = IntSize((crop.width * scale).roundToInt(), (crop.height * scale).roundToInt())
    val density = Density(max(size.width, size.height) / EXPORT_WIDTH_DP)
    val pointNumbers = numbers(density)
    val image = ImageBitmap(size.width, size.height)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), size.toSize()) {
        drawImage(
            photo.asImageBitmap(),
            srcOffset = IntOffset(crop.left.roundToInt(), crop.top.roundToInt()),
            srcSize = IntSize(crop.width.roundToInt(), crop.height.roundToInt()),
            dstSize = size,
        )
        points.forEachIndexed { index, point ->
            val center = Offset((point.x - crop.left) * scale, (point.y - crop.top) * scale)
            val color = if (point in uncertain) UNCERTAIN_COLOR else pointColor
            drawPoint(center, pointNumbers[index + 1], color)
        }
    }
    return image.asAndroidBitmap()
}

/** The system's share sheet for the last export, with saving it to the gallery on top. */
private fun shareSheet(context: Context): Intent {
    val uri =
        FileProvider.getUriForFile(context, "${context.packageName}.photos", exportFile(context))
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip lets the share sheet show the image as a preview.
    send.clipData = ClipData.newRawUri(null, uri)
    val chooser = Intent.createChooser(send, null)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val save =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setAction(ACTION_SAVE_EXPORT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val action =
            ChooserAction.Builder(
                    Icon.createWithResource(context, R.drawable.ic_save),
                    context.getString(R.string.save_to_gallery),
                    save,
                )
                .build()
        chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(action))
    }
    return chooser
}

/**
 * Where the export goes: always the same file in the cache, so it stays out of the gallery until
 * saved there, and the next export replaces it.
 */
private fun exportFile(context: Context) =
    File(context.cacheDir, "exports/count.jpg").apply { parentFile?.mkdirs() }
