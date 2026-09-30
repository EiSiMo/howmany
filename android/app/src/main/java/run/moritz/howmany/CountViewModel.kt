package run.moritz.howmany

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.max
import kotlin.math.min
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.CountResult
import run.moritz.howmany.counting.Heatmap
import run.moritz.howmany.counting.ObjectCounter
import run.moritz.howmany.counting.Point

private const val TAG = "CountViewModel"
// The model never sees more than 1024 pixels per side; this leaves headroom for display.
private const val MAX_PHOTO_SIZE = 2048

data class CountState(
    val photo: Bitmap? = null,
    /**
     * The part of the photo to count in; the whole photo unless the user drags its edges. Inside
     * the counted area once counting has started.
     */
    val crop: Box? = photo?.whole(),
    /**
     * The part of the photo the model counts in: the crop as counting started; null until then.
     * There are no points outside it, so the crop can shrink and grow back, but never beyond it.
     */
    val countedArea: Box? = null,
    val exemplar: Box? = null,
    /**
     * One point per counted object in the counted area, corrected by the user, including points
     * outside the crop; null until counted.
     */
    val points: List<Point>? = null,
    /** The counted points the model is unsure about, which the user should check. */
    val uncertain: Set<Point> = emptySet(),
    /** Where the model saw objects when counting; null until counted. */
    val heatmap: Heatmap? = null,
    val counting: Boolean = false,
    /** Whether a picked or taken photo is being read, so the empty frame need not ask for one. */
    val loadingPhoto: Boolean = false,
    /** What went wrong last, until the user moves on; null if nothing did. */
    val error: CountError? = null,
) {
    /** The counted points inside the crop. */
    val counted: List<Point>?
        get() = points?.filter { crop == null || it in crop }

    /** Where the user is on the way from picking a photo to a count. */
    val phase: CountPhase
        get() =
            when {
                // The crop comes with the photo.
                crop == null -> CountPhase.Empty
                points != null -> CountPhase.Counted
                counting -> CountPhase.Counting
                exemplar == null -> CountPhase.Marking
                else -> CountPhase.Ready
            }

    /** Counts only inside [crop] from now on, kept inside the counted area; clears the error. */
    fun cropped(crop: Box): CountState =
        if (crop == this.crop) this
        else copy(crop = countedArea?.let(crop::coercedIn) ?: crop, error = null)

    /** Starts counting in the crop, which becomes the counted area. */
    fun countingStarted(): CountState = copy(counting = true, countedArea = crop, error = null)

    /** The count has arrived, as [result]; only its points inside the crop count. */
    fun withCount(result: CountResult): CountState {
        val points = result.detections.map { it.box.center }
        return copy(
            points = points,
            uncertain = result.detections.filter { it.uncertain }.map { it.box.center }.toSet(),
            heatmap = result.heatmap,
            counting = false,
            error = null,
        )
    }

    /** Counting has failed; the crop is free again. */
    fun countFailed(): CountState =
        copy(counting = false, countedArea = null, error = CountError.CountFailed)

    /** Whether starting over changes anything: there is an exemplar, or the crop is not whole. */
    val canStartOver: Boolean
        get() = exemplar != null || crop != photo?.whole()

    /**
     * Starts over one step: forgets the exemplar and the count, keeping the photo and its crop, or
     * with neither of them, crops the whole photo again.
     */
    fun cleared(): CountState =
        if (exemplar != null) CountState(photo = photo, crop = crop) else CountState(photo = photo)
}

/** All of this photo, in its pixels. */
private fun Bitmap.whole() = Box(0f, 0f, width.toFloat(), height.toFloat())

/** This box cut to [limit]. */
private fun Box.coercedIn(limit: Box) =
    Box(
        max(left, limit.left),
        max(top, limit.top),
        min(right, limit.right),
        min(bottom, limit.bottom),
    )

enum class CountPhase {
    /** No photo yet. */
    Empty,
    /** A photo, waiting for the user to mark an exemplar. */
    Marking,
    /** An exemplar is marked; the user may adjust the crop or count. */
    Ready,
    /** The model is counting. */
    Counting,
    /** Counted; the user may correct the points. */
    Counted,
}

/** What can go wrong on the way to a count. */
enum class CountError(@StringRes val message: Int) {
    /** The picked photo cannot be read. */
    PhotoUnreadable(R.string.error_photo_unreadable),
    /** No camera app can take a photo. */
    NoCamera(R.string.error_no_camera),
    /** Counting failed; the user may try again. */
    CountFailed(R.string.error_count_failed),
    /** The count cannot be exported or saved. */
    ExportFailed(R.string.error_export_failed),
}

class CountViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(CountState())
    val state: StateFlow<CountState> = _state

    // Every session is about counting, so prepare the counter (copying the model out of the APK
    // and loading it, seconds on a phone) in the background right away, while the user picks a
    // photo and marks an exemplar. It holds only the model weights until the first count.
    private val objectCounter = Preloaded {
        val (counter, duration) =
            try {
                measureTimedValue { ObjectCounter.fromAssets(application) }
            } catch (e: Exception) {
                // Surfaces only once the user counts, which may be never.
                Log.e(TAG, "Cannot prepare the counter", e)
                throw e
            }
        Log.i(TAG, "Counter ready in $duration")
        counter
    }
    private var photoJob: Job? = null
    private var countJob: Job? = null

    /** Starts over with the photo at [uri], unless it cannot be read; replaces an earlier pick. */
    fun pickPhoto(uri: Uri) {
        photoJob?.cancel()
        _state.update { it.copy(loadingPhoto = true) }
        photoJob = viewModelScope.launch {
            val photo =
                try {
                    withContext(Dispatchers.IO) { decode(uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot read photo $uri", e)
                    _state.update {
                        it.copy(loadingPhoto = false, error = CountError.PhotoUnreadable)
                    }
                    return@launch
                }
            countJob?.cancel()
            _state.value = CountState(photo = photo)
        }
    }

    /** Tells the user that no camera app can take a photo, keeping everything else. */
    fun noCamera() = _state.update { it.copy(error = CountError.NoCamera) }

    /** Tells the user that the count cannot be exported, keeping everything else. */
    fun exportFailed() = _state.update { it.copy(error = CountError.ExportFailed) }

    /** Marks one object as the exemplar of what to count; only before counting. */
    fun markExemplar(box: Box) = _state.update {
        if (it.points == null) it.copy(exemplar = box, error = null) else it
    }

    /**
     * Removes the counted point nearest to [at] within [hitRadius], or adds one at [at], inside the
     * crop.
     */
    fun toggle(at: Point, hitRadius: Float) = _state.update { state ->
        val points = state.points ?: return@update state
        val exemplar = state.exemplar ?: return@update state
        state.copy(
            points = points.toggled(at, hitRadius, exemplar.height, state.crop),
            error = null,
        )
    }

    /**
     * Counts only inside [crop] from now on, within the counted area once counting has started;
     * after counting, this corrects the count.
     */
    fun adjustCrop(crop: Box) = _state.update { it.cropped(crop) }

    /**
     * Forgets the exemplar, the count and the counted area, keeping the photo and its crop; with
     * nothing of them left, crops the whole photo again.
     */
    fun clear() {
        countJob?.cancel()
        _state.update { it.cleared() }
    }

    fun count() {
        val photo = _state.value.photo ?: return
        val exemplar = _state.value.exemplar ?: return
        val crop = _state.value.crop ?: return
        if (_state.value.counting) return
        _state.update { it.countingStarted() }
        val cancelled = countJob
        countJob = viewModelScope.launch {
            // A cancelled count still occupies the model until it returns; don't run two at once.
            cancelled?.join()
            Log.i(TAG, "Counting in $crop like $exemplar")
            val (result, duration) =
                try {
                    // Waits for the counter if it is still being prepared; a preparation error
                    // fails here.
                    val counter = objectCounter.get()
                    withContext(Dispatchers.Default) {
                        measureTimedValue { counter.detect(photo, listOf(exemplar), crop) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot count in $crop like $exemplar", e)
                    _state.update { it.countFailed() }
                    return@launch
                }
            Log.i(TAG, "${result.detections.size} objects in $duration")
            _state.update { it.withCount(result) }
        }
    }

    override fun onCleared() = objectCounter.close()

    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        var original: Size? = null
        val (photo, duration) =
            measureTimedValue {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val size = info.size.also { original = it }
                    val factor = MAX_PHOTO_SIZE.toFloat() / max(size.width, size.height)
                    if (factor < 1) {
                        decoder.setTargetSize(
                            (size.width * factor).toInt(),
                            (size.height * factor).toInt(),
                        )
                    }
                }
            }
        Log.i(TAG, "Decoded $original photo to ${photo.width}x${photo.height} in $duration")
        return photo
    }
}
