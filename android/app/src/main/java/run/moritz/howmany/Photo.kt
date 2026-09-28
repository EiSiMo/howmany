package run.moritz.howmany

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import run.moritz.howmany.counting.Box as ImageBox
import run.moritz.howmany.counting.Heatmap
import run.moritz.howmany.counting.Point

// Taps within the hit radius of a point hit it, at any zoom.
private val HIT_RADIUS = 24.dp
// The crop's edges can be grabbed this far from them, and it never gets smaller than a finger.
val HANDLE_REACH = 24.dp
// Android excludes at most 200dp of back gesture per screen edge; the three side handles' boxes
// get as tall as fits.
private val SIDE_HANDLE_EXCLUSION_HEIGHT = 66.dp
private val MIN_CROP_SIZE = 48.dp
private const val CROPPED_ALPHA = 0.6f

/**
 * Shows the photo with its crop and the [exemplarFrame] (the exemplar until counted) or the counted
 * points, the [uncertain] ones highlighted. While [counting], it zooms smoothly out to the whole
 * photo and scans from the edge of [exemplar]; when the count arrives, it reveals the points and
 * their [heatmap] from there, as [animation] goes. Two fingers zoom and pan. One finger drags the
 * crop's edges while [onAdjustCrop] is given; elsewhere it drags a box around one object while
 * [onMarkExemplar] is given, and pans otherwise, keeping the crop inside the [countedArea], if
 * given, or the photo. Taps go to [onTap], with a hit radius in image pixels. While
 * [zoomOnDoubleTap], a double tap zooms in around it, or out to the whole photo.
 */
@Composable
fun Photo(
    photo: Bitmap,
    crop: ImageBox,
    countedArea: ImageBox?,
    margin: Margin,
    exemplarFrame: ImageBox?,
    points: List<Point>,
    uncertain: Set<Point>,
    exemplar: ImageBox?,
    heatmap: Heatmap?,
    counting: Boolean,
    animation: CountingAnimation,
    onAdjustCrop: ((ImageBox) -> Unit)?,
    onMarkExemplar: ((ImageBox) -> Unit)?,
    onTap: ((at: Point, hitRadius: Float) -> Unit)?,
    zoomOnDoubleTap: Boolean,
    modifier: Modifier = Modifier,
) {
    val image = remember(photo) { photo.asImageBitmap() }
    val bounds = ImageBox(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
    var viewport by remember(photo) { mutableStateOf<Viewport?>(null) }
    var drag by remember(photo) { mutableStateOf<PhotoDrag?>(null) }
    val currentCrop by rememberUpdatedState(crop)
    val cropLimit by rememberUpdatedState(countedArea ?: bounds)
    val currentExemplarFrame by rememberUpdatedState(exemplarFrame)
    val adjustCrop by rememberUpdatedState(onAdjustCrop)
    val markExemplar by rememberUpdatedState(onMarkExemplar)
    val tap by rememberUpdatedState(onTap)
    val pointColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.primary
    val numbers = rememberPointNumbers()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Moving the photo by hand or counting stops a double tap's zoom on its way.
    var zooming by remember { mutableStateOf<Job?>(null) }
    var viewSize by remember { mutableStateOf<Size?>(null) }
    LaunchedEffect(photo, viewSize, margin) {
        viewport = viewSize?.let {
            Viewport.fit(it, Size(photo.width.toFloat(), photo.height.toFloat()), margin)
        }
    }

    // The crop follows a drag at once, but glides to where starting over puts it.
    var settledCrop by remember(photo) { mutableStateOf(crop) }
    var glidingCrop by remember(photo) { mutableStateOf<ImageBox?>(null) }
    LaunchedEffect(crop) {
        val from = glidingCrop ?: settledCrop
        settledCrop = crop
        if (drag !is PhotoDrag.Crop && from != crop) {
            animate(0f, 1f) { fraction, _ -> glidingCrop = from.toward(crop, fraction) }
        }
        glidingCrop = null
    }
    val shownCrop = if (drag is PhotoDrag.Crop) crop else glidingCrop ?: settledCrop

    LaunchedEffect(counting) {
        val from = viewport
        if (counting && from != null) {
            zooming?.cancel()
            animate(0f, 1f) { fraction, _ -> viewport = from.zoomedOut(fraction) }
        }
    }
    // Contours shimmer at the scale counting zooms out to.
    LaunchedEffect(counting) {
        val size = viewSize
        if (counting && size != null) {
            val fitted =
                Viewport.fit(size, Size(photo.width.toFloat(), photo.height.toFloat()), margin)
            animation.measureContours(photo, crop, fitted.scale / density.density)
        }
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it.toSize() }
            .pointerInput(photo, zoomOnDoubleTap) {
                detectPhotoGestures(
                    onTap = { position ->
                        val current = viewport
                        if (current != null) {
                            val at = current.toImage(position)
                            tap?.invoke(Point(at.x, at.y), HIT_RADIUS.toPx() / current.scale)
                        }
                    },
                    onDoubleTap =
                        if (!zoomOnDoubleTap) null
                        else
                            { position ->
                                viewport?.let { from ->
                                    val to = from.doubleTapped(position)
                                    zooming?.cancel()
                                    zooming = scope.launch {
                                        animate(0f, 1f) { fraction, _ ->
                                            viewport = from.toward(to, fraction)
                                        }
                                    }
                                }
                            },
                    onDrag = onDrag@{ start, position, delta ->
                            val current = viewport ?: return@onDrag
                            val kind =
                                drag
                                    ?: photoDrag(
                                        start,
                                        currentCrop,
                                        current,
                                        HANDLE_REACH.toPx(),
                                        canAdjustCrop = adjustCrop != null,
                                        canMarkExemplar = markExemplar != null,
                                    )
                            drag =
                                when (kind) {
                                    is PhotoDrag.Crop -> {
                                        val moved =
                                            kind.from.dragged(
                                                kind.handle,
                                                (position - start) / current.scale,
                                                cropLimit,
                                                keep = currentExemplarFrame,
                                                minSize = MIN_CROP_SIZE.toPx() / current.scale,
                                            )
                                        adjustCrop?.invoke(moved)
                                        kind
                                    }
                                    is PhotoDrag.Exemplar -> kind.copy(end = position)
                                    PhotoDrag.Pan -> {
                                        zooming?.cancel()
                                        viewport = current.transformed(Offset.Zero, 1f, delta)
                                        kind
                                    }
                                }
                        },
                    onDragEnd = {
                        val current = viewport
                        val dragged = drag
                        if (current != null && dragged is PhotoDrag.Exemplar) {
                            dragged.box(current, currentCrop)?.let { markExemplar?.invoke(it) }
                        }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                    onTransform = { centroid, zoom, pan ->
                        zooming?.cancel()
                        viewport = viewport?.transformed(centroid, zoom, pan)
                    },
                )
            }
    ) {
        // Only the photo shimmers and bends; frames and points stay crisp on top.
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                val current = viewport
                val source = exemplar
                renderEffect =
                    if (current == null || source == null) null
                    else
                        animation.distortion(
                            source.inView(current),
                            crop.inView(current),
                            heatmap?.bounds?.inView(current),
                            density,
                        )
            }
        ) {
            val current = viewport ?: return@Canvas
            val photoRect = bounds.inView(current)
            drawImage(
                image,
                dstOffset = IntOffset(photoRect.left.roundToInt(), photoRect.top.roundToInt()),
                dstSize = IntSize(photoRect.width.roundToInt(), photoRect.height.roundToInt()),
            )
            val cropRect = shownCrop.inView(current)
            clipPath(cropOutline(cropRect), ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = CROPPED_ALPHA), photoRect.topLeft, photoRect.size)
            }
            if (exemplar != null) {
                with(animation) {
                    drawCountingAnimation(
                        cropRect,
                        heatmap,
                        heatmap?.bounds?.inView(current),
                        pointColor,
                    )
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val current = viewport ?: return@Canvas
            drawCropHandles(shownCrop.inView(current), handleColor)
            points.forEachIndexed { index, point ->
                val color = if (point in uncertain) UNCERTAIN_COLOR else pointColor
                val center = current.toView(Offset(point.x, point.y))
                val scale = animation.pointScale(point)
                if (scale > 0) {
                    scale(scale, center) { drawPoint(center, numbers[index + 1], color) }
                }
            }
            val dragged = drag
            val rect =
                when {
                    dragged is PhotoDrag.Exemplar -> dragged.rect
                    exemplarFrame != null -> exemplarFrame.inView(current)
                    else -> null
                }
            if (rect != null) drawExemplarFrame(rect)
        }
        if (onAdjustCrop != null) {
            for (handle in SIDE_HANDLES) {
                Box(
                    Modifier.offset {
                            val current = viewport ?: return@offset IntOffset.Zero
                            val at = handle(shownCrop.inView(current))
                            val halfHeight = SIDE_HANDLE_EXCLUSION_HEIGHT.toPx() / 2
                            (at - Offset(HANDLE_REACH.toPx(), halfHeight)).round()
                        }
                        .size(width = HANDLE_REACH * 2, height = SIDE_HANDLE_EXCLUSION_HEIGHT)
                        .systemGestureExclusion()
                )
            }
        }
    }
}

/** The box [fraction] of the way from this one to [target]. */
private fun ImageBox.toward(target: ImageBox, fraction: Float) =
    ImageBox(
        lerp(left, target.left, fraction),
        lerp(top, target.top, fraction),
        lerp(right, target.right, fraction),
        lerp(bottom, target.bottom, fraction),
    )
