package run.moritz.howmany

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import run.moritz.howmany.counting.Box
import run.moritz.howmany.counting.Point

private const val TAG = "SavedCount"
private const val PHOTO = "photo"
private const val CROP = "crop"
private const val COUNTED_AREA = "countedArea"
private const val EXEMPLAR = "exemplar"
private const val POINTS = "points"
private const val UNCERTAIN = "uncertain"

/**
 * Keeps the count across Android ending the app in the background: the photo as a file in
 * [directory], the rest in [handle], which Android keeps while the user may return to the app. Used
 * from the main thread.
 */
class SavedCount(private val handle: SavedStateHandle, private val directory: File) {
    /** Whether there is a count to [restore]. */
    val hasCount: Boolean
        get() = handle.get<String>(PHOTO) != null

    /** Remembers [state] apart from its photo, which [savePhoto] keeps. */
    fun save(state: CountState) = handle.saveCount(state)

    /** Forgets the saved photo, as another is about to replace it. */
    fun forgetPhoto() {
        handle[PHOTO] = null
    }

    /** Keeps [photo] as a file, replacing the one kept before, unless cancelled first. */
    suspend fun savePhoto(photo: Bitmap) {
        val file =
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                val file = File(directory, "${UUID.randomUUID()}.png")
                val partial = File(directory, "${file.name}.partial")
                // Lossless, so a restored photo counts the same.
                partial.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(partial.renameTo(file)) { "Cannot rename $partial to $file" }
                file
            }
        // A photo picked meanwhile has cancelled this one.
        currentCoroutineContext().ensureActive()
        handle[PHOTO] = file.name
        withContext(Dispatchers.IO) {
            directory.listFiles()?.filter { it != file }?.forEach { it.delete() }
        }
    }

    /**
     * The count saved before Android ended the app, with its photo; null if there is none, or its
     * photo has gone.
     */
    suspend fun restore(): CountState? {
        val name = handle.get<String>(PHOTO) ?: return null
        val count = handle.restoredCount() ?: return null
        val file = File(directory, name)
        val photo =
            withContext(Dispatchers.IO) {
                if (!file.exists()) {
                    Log.w(TAG, "The saved photo $file has gone")
                    return@withContext null
                }
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } ?: return null
        return count.copy(photo = photo)
    }
}

/**
 * Remembers [state] apart from its photo; a count still running is lost, so is the error, and the
 * heatmap: it alone can exceed what Android passes on as the app goes to the background, which
 * crashes the app, and a restored count need not glow.
 */
internal fun SavedStateHandle.saveCount(state: CountState) {
    val counted = state.points != null
    this[CROP] = state.crop?.toArray()
    this[COUNTED_AREA] = state.countedArea?.takeIf { counted }?.toArray()
    this[EXEMPLAR] = state.exemplar?.toArray()
    this[POINTS] = state.points?.toArray()
    this[UNCERTAIN] = state.uncertain.toList().toArray()
}

/** The count [saveCount] remembered, without its photo; null if it had no photo. */
internal fun SavedStateHandle.restoredCount(): CountState? {
    val crop = get<FloatArray>(CROP)?.toBox() ?: return null
    return CountState(
        crop = crop,
        countedArea = get<FloatArray>(COUNTED_AREA)?.toBox(),
        exemplar = get<FloatArray>(EXEMPLAR)?.toBox(),
        points = get<FloatArray>(POINTS)?.toPoints(),
        uncertain = get<FloatArray>(UNCERTAIN)?.toPoints().orEmpty().toSet(),
    )
}

private fun Box.toArray() = floatArrayOf(left, top, right, bottom)

private fun FloatArray.toBox() = Box(this[0], this[1], this[2], this[3])

private fun List<Point>.toArray() =
    FloatArray(size * 2) { if (it % 2 == 0) this[it / 2].x else this[it / 2].y }

private fun FloatArray.toPoints() = (indices step 2).map { Point(this[it], this[it + 1]) }
