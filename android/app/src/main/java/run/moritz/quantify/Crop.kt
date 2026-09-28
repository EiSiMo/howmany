package run.moritz.quantify

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import run.moritz.quantify.counting.Box

/** The edges of the crop that move together when the user drags them: one edge or a corner. */
data class CropHandle(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
)

/** The edges of [crop] within [reach] of [position], all in view pixels, or null for none. */
fun cropHandleAt(crop: Rect, position: Offset, reach: Float): CropHandle? {
    val withinX = position.x in crop.left - reach..crop.right + reach
    val withinY = position.y in crop.top - reach..crop.bottom + reach
    val handle =
        CropHandle(
            left = withinY && abs(position.x - crop.left) <= reach,
            top = withinX && abs(position.y - crop.top) <= reach,
            right = withinY && abs(position.x - crop.right) <= reach,
            bottom = withinX && abs(position.y - crop.bottom) <= reach,
        )
    return handle.takeIf { it != CropHandle() }
}

/**
 * The crop with the edges of [handle] moved by [delta], all in image pixels. It stays inside
 * [within] (the photo, or the counted area), at least [minSize] wide and high, and around [keep],
 * if given.
 */
fun Box.dragged(
    handle: CropHandle,
    delta: Offset,
    within: Box,
    keep: Box? = null,
    minSize: Float = 0f,
): Box {
    fun Float.limited(lowest: Float, highest: Float) = coerceAtLeast(lowest).coerceAtMost(highest)
    return Box(
        if (!handle.left) left
        else (left + delta.x).limited(within.left, min(right - minSize, keep?.left ?: right)),
        if (!handle.top) top
        else (top + delta.y).limited(within.top, min(bottom - minSize, keep?.top ?: bottom)),
        if (!handle.right) right
        else (right + delta.x).limited(max(left + minSize, keep?.right ?: left), within.right),
        if (!handle.bottom) bottom
        else (bottom + delta.y).limited(max(top + minSize, keep?.bottom ?: top), within.bottom),
    )
}
