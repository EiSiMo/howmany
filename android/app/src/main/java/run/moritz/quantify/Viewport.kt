package run.moritz.quantify

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.min

// How far the user can zoom in, relative to the photo fitted into the view.
private const val MAX_ZOOM = 8f

/** Room kept free between the photo and each edge of the view, in view pixels. */
data class Margin(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    constructor(all: Float) : this(all, all, all, all)
}

/**
 * How a photo is shown in a view: fitted into it with a margin, then zoomed and panned by the user.
 * A point in image pixels lies at `image * scale + offset` in view pixels.
 */
class Viewport
private constructor(
    private val viewSize: Size,
    private val imageSize: Size,
    private val margin: Margin,
    /** View pixels per image pixel. */
    val scale: Float,
    private val offset: Offset,
) {
    private val fitScale = fitScale(viewSize, imageSize, margin)

    /** Zooms by [zoom] around [centroid] and then pans by [pan], all in view pixels. */
    fun transformed(centroid: Offset, zoom: Float, pan: Offset): Viewport {
        val newScale = (scale * zoom).coerceIn(fitScale, fitScale * MAX_ZOOM)
        // The image point under the centroid stays there.
        val newOffset = centroid - (centroid - offset) * (newScale / scale) + pan
        return Viewport(viewSize, imageSize, margin, newScale, clamped(newOffset, newScale))
    }

    /**
     * The way from here to the whole photo fitted into the view: this at [fraction] 0, fitted at 1.
     * Every image point moves on a straight line.
     */
    fun zoomedOut(fraction: Float): Viewport {
        val fitted = fit(viewSize, imageSize, margin)
        return Viewport(
            viewSize,
            imageSize,
            margin,
            lerp(scale, fitted.scale, fraction),
            lerp(offset, fitted.offset, fraction),
        )
    }

    fun toView(image: Offset) = image * scale + offset

    fun toImage(view: Offset) = (view - offset) / scale

    /**
     * Centers the photo along an axis it doesn't fill, and never shows more than the margin
     * otherwise.
     */
    private fun clamped(offset: Offset, scale: Float): Offset {
        fun axis(offset: Float, view: Float, image: Float, start: Float, end: Float): Float {
            val free = view - image * scale
            return if (free >= start + end) start + (free - start - end) / 2
            else offset.coerceIn(free - end, start)
        }
        return Offset(
            axis(offset.x, viewSize.width, imageSize.width, margin.left, margin.right),
            axis(offset.y, viewSize.height, imageSize.height, margin.top, margin.bottom),
        )
    }

    companion object {
        /** The photo fitted into the view, [margin] away from its edges, centered in between. */
        fun fit(viewSize: Size, imageSize: Size, margin: Margin = Margin(0f)): Viewport {
            val scale = fitScale(viewSize, imageSize, margin)
            val offset =
                Offset(
                    margin.left +
                        (viewSize.width - margin.left - margin.right - imageSize.width * scale) / 2,
                    margin.top +
                        (viewSize.height - margin.top - margin.bottom - imageSize.height * scale) /
                            2,
                )
            return Viewport(viewSize, imageSize, margin, scale, offset)
        }

        private fun fitScale(viewSize: Size, imageSize: Size, margin: Margin) =
            min(
                (viewSize.width - margin.left - margin.right) / imageSize.width,
                (viewSize.height - margin.top - margin.bottom) / imageSize.height,
            )
    }
}
