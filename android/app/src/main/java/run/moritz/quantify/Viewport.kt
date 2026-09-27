package run.moritz.quantify

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.min

// How far the user can zoom in, relative to the photo fitted into the view.
private const val MAX_ZOOM = 8f

/**
 * How a photo is shown in a view: fitted into it, then zoomed and panned by the user. A point in
 * image pixels lies at `image * scale + offset` in view pixels.
 */
class Viewport
private constructor(
    private val viewSize: Size,
    private val imageSize: Size,
    /** View pixels per image pixel. */
    val scale: Float,
    private val offset: Offset,
) {
    private val fitScale = fitScale(viewSize, imageSize)

    /** Zooms by [zoom] around [centroid] and then pans by [pan], all in view pixels. */
    fun transformed(centroid: Offset, zoom: Float, pan: Offset): Viewport {
        val newScale = (scale * zoom).coerceIn(fitScale, fitScale * MAX_ZOOM)
        // The image point under the centroid stays there.
        val newOffset = centroid - (centroid - offset) * (newScale / scale) + pan
        return Viewport(viewSize, imageSize, newScale, clamped(newOffset, newScale))
    }

    fun toView(image: Offset) = image * scale + offset

    fun toImage(view: Offset) = (view - offset) / scale

    /** Centers the photo along an axis it doesn't fill, and never shows empty space otherwise. */
    private fun clamped(offset: Offset, scale: Float): Offset {
        fun axis(offset: Float, view: Float, image: Float): Float {
            val free = view - image * scale
            return if (free >= 0) free / 2 else offset.coerceIn(free, 0f)
        }
        return Offset(
            axis(offset.x, viewSize.width, imageSize.width),
            axis(offset.y, viewSize.height, imageSize.height),
        )
    }

    companion object {
        /** The photo fitted into the view and centered. */
        fun fit(viewSize: Size, imageSize: Size): Viewport {
            val scale = fitScale(viewSize, imageSize)
            val offset =
                Offset(
                    (viewSize.width - imageSize.width * scale) / 2,
                    (viewSize.height - imageSize.height * scale) / 2,
                )
            return Viewport(viewSize, imageSize, scale, offset)
        }

        private fun fitScale(viewSize: Size, imageSize: Size) =
            min(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
    }
}
