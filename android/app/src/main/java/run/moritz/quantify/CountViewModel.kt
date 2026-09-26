package run.moritz.quantify

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.measureTimedValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.moritz.quantify.counting.Box
import run.moritz.quantify.counting.MODEL_ASSET
import run.moritz.quantify.counting.ObjectCounter

private const val TAG = "CountViewModel"
// The model never sees more than 1024 pixels per side; this leaves headroom for display.
private const val MAX_PHOTO_SIZE = 2048

data class CountState(
    val photo: Bitmap? = null,
    val exemplar: Box? = null,
    val detections: List<Box>? = null,
    val duration: Duration? = null,
    val counting: Boolean = false,
)

class CountViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(CountState())
    val state: StateFlow<CountState> = _state

    private val counter by lazy { ObjectCounter(modelFile()) }

    fun pickPhoto(uri: Uri) {
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) { decode(uri) }
            _state.value = CountState(photo = photo)
        }
    }

    fun markExemplar(box: Box) = _state.update { it.copy(exemplar = box, detections = null) }

    fun count() {
        val photo = _state.value.photo ?: return
        val exemplar = _state.value.exemplar ?: return
        _state.update { it.copy(counting = true) }
        viewModelScope.launch {
            val (detections, duration) =
                withContext(Dispatchers.Default) {
                    measureTimedValue { counter.detect(photo, listOf(exemplar)) }
                }
            Log.i(TAG, "${detections.size} objects in $duration")
            _state.update {
                it.copy(detections = detections, duration = duration, counting = false)
            }
        }
    }

    override fun onCleared() = counter.close()

    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size
            val factor = MAX_PHOTO_SIZE.toFloat() / max(size.width, size.height)
            if (factor < 1) {
                decoder.setTargetSize((size.width * factor).toInt(), (size.height * factor).toInt())
            }
        }
    }

    /** ONNX Runtime needs the model as a file; copy it out of the APK once per app version. */
    private fun modelFile(): File {
        val context = getApplication<Application>()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val file = File(context.noBackupFilesDir, "$version-$MODEL_ASSET")
        if (!file.exists()) {
            context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
            val partial = File(context.noBackupFilesDir, "$MODEL_ASSET.partial")
            context.assets.open(MODEL_ASSET).use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.renameTo(file)) { "Cannot move model to $file" }
        }
        return file
    }
}
