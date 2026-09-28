package run.moritz.howmany

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import run.moritz.howmany.counting.Box as ImageBox

// A second tap counts toward a double tap this far from the first, as in Android's views.
private val DOUBLE_TAP_SLOP = 100.dp
// An exemplar needs to be more than this many image pixels wide and high.
private const val MIN_EXEMPLAR_SIZE = 1f

/** What a one-finger drag on the photo does, decided where it starts. */
sealed interface PhotoDrag {
    /** Moves the edges of [handle], starting from the crop as it was, [from]. */
    data class Crop(val handle: CropHandle, val from: ImageBox) : PhotoDrag

    /** Drags a box around one object from [start] to [end], in view pixels. */
    data class Exemplar(val start: Offset, val end: Offset) : PhotoDrag {
        /** The box dragged so far, in view pixels. */
        val rect: Rect
            get() = spanned(start, end)

        /** The dragged box in image pixels, cut to [crop]; null if too small to be an exemplar. */
        fun box(viewport: Viewport, crop: ImageBox): ImageBox? {
            val box =
                spanned(viewport.toImage(start), viewport.toImage(end))
                    .intersect(Rect(crop.left, crop.top, crop.right, crop.bottom))
            return ImageBox(box.left, box.top, box.right, box.bottom).takeIf {
                it.width > MIN_EXEMPLAR_SIZE && it.height > MIN_EXEMPLAR_SIZE
            }
        }
    }

    /** Moves the photo in the view. */
    data object Pan : PhotoDrag
}

/**
 * What a drag from [start] (in view pixels) does: it adjusts the [crop] when it starts within
 * [handleReach] of the crop's edges and [canAdjustCrop]; otherwise it marks an exemplar if
 * [canMarkExemplar], or pans.
 */
fun photoDrag(
    start: Offset,
    crop: ImageBox,
    viewport: Viewport,
    handleReach: Float,
    canAdjustCrop: Boolean,
    canMarkExemplar: Boolean,
): PhotoDrag =
    cropHandleAt(crop.inView(viewport), start, handleReach)
        ?.takeIf { canAdjustCrop }
        ?.let { PhotoDrag.Crop(it, crop) }
        ?: if (canMarkExemplar) PhotoDrag.Exemplar(start, start) else PhotoDrag.Pan

/** The rect with corners [a] and [b], whichever way they lie to each other. */
private fun spanned(a: Offset, b: Offset) =
    Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

/**
 * Whether a second tap starting at [second], [elapsedMillis] after a tap at [first] ended, makes a
 * double tap: it comes within the time [window] and no farther than [slop] away.
 */
fun isDoubleTap(
    first: Offset,
    second: Offset,
    elapsedMillis: Long,
    window: LongRange,
    slop: Float,
) = elapsedMillis in window && (second - first).getDistance() <= slop

/**
 * One finger taps or drags (from `start`, now at `position`, moved by `delta` since the last call);
 * two fingers zoom and pan. A second finger cancels a drag. While [onDoubleTap] is given, a tap
 * waits for a second one to make a double tap, and only goes to [onTap] if none comes.
 */
suspend fun PointerInputScope.detectPhotoGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: ((Offset) -> Unit)?,
    onDrag: (start: Offset, position: Offset, delta: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onTransform: (centroid: Offset, zoom: Float, pan: Offset) -> Unit,
) = awaitEachGesture {
    val window = viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis
    val slop = DOUBLE_TAP_SLOP.toPx()
    var down = awaitFirstDown()
    while (true) {
        val tapEnd =
            awaitTap(down, onDrag, onDragEnd, onDragCancel, onTransform) ?: return@awaitEachGesture
        if (onDoubleTap == null) return@awaitEachGesture onTap(down.position)
        val next =
            withTimeoutOrNull(window.last) { awaitFirstDown() }
                ?: return@awaitEachGesture onTap(down.position)
        if (isDoubleTap(down.position, next.position, next.uptimeMillis - tapEnd, window, slop)) {
            if (awaitTap(next, onDrag, onDragEnd, onDragCancel, onTransform) != null) {
                onDoubleTap(next.position)
            }
            return@awaitEachGesture
        }
        // Not a double tap: the first was a single tap, and the next one starts over.
        onTap(down.position)
        down = next
    }
}

/**
 * Follows the pointers from [down] until all are up, dragging or transforming as they go. Returns
 * when the last one went up (in uptime milliseconds) if it was a tap, null otherwise.
 */
private suspend fun AwaitPointerEventScope.awaitTap(
    down: PointerInputChange,
    onDrag: (start: Offset, position: Offset, delta: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onTransform: (centroid: Offset, zoom: Float, pan: Offset) -> Unit,
): Long? {
    var dragging = false
    var transforming = false
    var upMillis: Long
    do {
        val event = awaitPointerEvent()
        upMillis = event.changes.maxOf { it.uptimeMillis }
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size >= 2) {
            if (dragging && !transforming) onDragCancel()
            transforming = true
            onTransform(event.calculateCentroid(), event.calculateZoom(), event.calculatePan())
            event.changes.forEach { it.consume() }
        } else if (!transforming && pressed.size == 1) {
            val change = pressed.single()
            if (!dragging) {
                dragging =
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
            if (dragging) {
                onDrag(down.position, change.position, change.positionChange())
                change.consume()
            }
        }
    } while (event.changes.any { it.pressed })
    return when {
        transforming -> null
        dragging -> {
            onDragEnd()
            null
        }
        else -> upMillis
    }
}
