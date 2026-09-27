package run.moritz.quantify

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import run.moritz.quantify.counting.Box as ImageBox
import run.moritz.quantify.counting.Heatmap
import run.moritz.quantify.counting.Point

// Points keep their size on screen at any zoom; taps within the hit radius hit them.
// Points are see-through, so the object underneath stays visible while correcting.
private val POINT_RADIUS = 10.dp
private val POINT_OUTLINE = 1.5.dp
private const val POINT_ALPHA = 0.45f
// Material has no warning color; yellow stands out from the theme's points on any photo.
private val UNCERTAIN_COLOR = Color(0xFFFFD600)
private val POINT_NUMBER_SIZE = 9.sp
private val HIT_RADIUS = 24.dp
// The exemplar frame is white with a soft dark halo, so it reads on any photo without a theme tint.
// Frames smaller than the full-size frame shrink as a whole, so the corners never merge.
private val EXEMPLAR_FULL_SIZE = 72.dp
private val EXEMPLAR_OUTLINE = 1.5.dp
private val EXEMPLAR_CORNER_STROKE = 3.5.dp
private val EXEMPLAR_CORNER_LENGTH = 16.dp
private val EXEMPLAR_RADIUS = 8.dp
private val EXEMPLAR_HALO = 2.dp
// Room around the photo, so its edges can be dragged without triggering the back gesture.
private val PHOTO_MARGIN = 24.dp
private val HANDLE_REACH = 24.dp
private val HANDLE_LENGTH = 20.dp
private val HANDLE_STROKE = 4.dp
private val MIN_CROP_SIZE = 48.dp
private const val CROPPED_ALPHA = 0.6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountScreen(viewModel: CountViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            uri?.let(viewModel::pickPhoto)
        }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
    val photo = state.photo
    val points = state.counted
    val animation =
        rememberCountingAnimation(
            state.counting,
            state.heatmap,
            state.exemplar,
            state.crop,
        )
    // The count rises with the points the reveal has shown so far.
    val revealed by
        remember(points) { derivedStateOf { points?.count { pointScale(animation, it) > 0 } ?: 0 } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (points != null) Count(revealed) else Text(stringResource(R.string.app_name))
                },
                actions = {
                    if (state.corrected) {
                        // Donating corrections as training data is not built yet.
                        IconButton(onClick = {}) {
                            Icon(
                                painterResource(R.drawable.ic_donate),
                                stringResource(R.string.donate),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (photo != null) {
                BottomAppBar(
                    actions = {
                        IconButton(onClick = pickPhoto) {
                            Icon(
                                painterResource(R.drawable.ic_pick_photo),
                                stringResource(R.string.pick_photo),
                            )
                        }
                        IconButton(onClick = viewModel::clear, enabled = state.exemplar != null) {
                            Icon(
                                painterResource(R.drawable.ic_clear),
                                stringResource(R.string.clear),
                            )
                        }
                    },
                    floatingActionButton = {
                        if (state.exemplar != null && points == null) {
                            ExtendedFloatingActionButton(
                                text = {
                                    Text(
                                        stringResource(
                                            if (state.counting) R.string.counting
                                            else R.string.count
                                        )
                                    )
                                },
                                icon = { Icon(painterResource(R.drawable.ic_count), null) },
                                onClick = viewModel::count,
                                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        val crop = state.crop
        if (photo == null || crop == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(pickPhoto)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                val hint =
                    when {
                        points != null -> R.string.correct
                        state.counting -> R.string.counting
                        state.exemplar == null -> R.string.mark_example
                        else -> R.string.adjust_crop
                    }
                Hint(stringResource(hint), Modifier.align(Alignment.CenterHorizontally))
                Photo(
                    photo = photo,
                    crop = crop,
                    exemplar = state.exemplar.takeIf { points == null },
                    points = points.orEmpty(),
                    uncertain = state.uncertain,
                    example = state.exemplar,
                    heatmap = state.heatmap,
                    counting = state.counting,
                    animation = animation,
                    onAdjustCrop = viewModel::adjustCrop.takeIf { !state.counting },
                    onMarkExemplar =
                        viewModel::markExemplar.takeIf { points == null && !state.counting },
                    onTap = viewModel::toggle.takeIf { points != null },
                    modifier =
                        Modifier.fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                )
            }
        }
    }
}

/** The number of counted objects, large, with a word what it is. */
@Composable
private fun Count(count: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            pluralStringResource(R.plurals.objects, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun EmptyState(onPickPhoto: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_count),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onPickPhoto,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                painterResource(R.drawable.ic_pick_photo),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                stringResource(R.string.pick_photo),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
    }
}

/** A short instruction above the photo. */
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** What a one-finger drag on the photo does, decided where it starts. */
private sealed interface PhotoDrag {
    data class Crop(val handle: CropHandle, val from: ImageBox) : PhotoDrag

    data class Exemplar(val start: Offset, val end: Offset) : PhotoDrag

    data object Pan : PhotoDrag
}

/**
 * Shows the photo with its crop and the example or the counted points, the [uncertain] ones
 * highlighted. While [counting], it zooms smoothly out to the whole photo and scans from the edge
 * of [example]; when the count arrives, it reveals the points and their [heatmap] from there, as
 * [animation] goes. Two fingers zoom and pan. One finger drags the crop's edges while
 * [onAdjustCrop] is given; elsewhere it drags a box around one object while [onMarkExemplar] is
 * given, and pans otherwise. Taps go to [onTap], with a hit radius in image pixels.
 */
@Composable
private fun Photo(
    photo: Bitmap,
    crop: ImageBox,
    exemplar: ImageBox?,
    points: List<Point>,
    uncertain: Set<Point>,
    example: ImageBox?,
    heatmap: Heatmap?,
    counting: Boolean,
    animation: CountingAnimation,
    onAdjustCrop: ((ImageBox) -> Unit)?,
    onMarkExemplar: ((ImageBox) -> Unit)?,
    onTap: ((at: Point, hitRadius: Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val image = remember(photo) { photo.asImageBitmap() }
    val bounds = ImageBox(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
    var viewport by remember(photo) { mutableStateOf<Viewport?>(null) }
    var drag by remember(photo) { mutableStateOf<PhotoDrag?>(null) }
    val currentCrop by rememberUpdatedState(crop)
    val currentExemplar by rememberUpdatedState(exemplar)
    val adjustCrop by rememberUpdatedState(onAdjustCrop)
    val markExemplar by rememberUpdatedState(onMarkExemplar)
    val tap by rememberUpdatedState(onTap)
    val pointColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val margin = with(density) { PHOTO_MARGIN.toPx() }

    LaunchedEffect(counting) {
        val from = viewport
        if (counting && from != null) {
            animate(0f, 1f) { fraction, _ -> viewport = from.zoomedOut(fraction) }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size ->
                viewport =
                    Viewport.fit(
                        size.toSize(),
                        Size(photo.width.toFloat(), photo.height.toFloat()),
                        Margin(margin),
                    )
            }
            .pointerInput(photo) {
                detectPhotoGestures(
                    onTap = { position ->
                        val current = viewport
                        if (current != null) {
                            val at = current.toImage(position)
                            tap?.invoke(Point(at.x, at.y), HIT_RADIUS.toPx() / current.scale)
                        }
                    },
                    onDrag = onDrag@{ start, position, delta ->
                            val current = viewport ?: return@onDrag
                            val kind =
                                drag
                                    ?: cropHandleAt(
                                            currentCrop.inView(current),
                                            start,
                                            HANDLE_REACH.toPx(),
                                        )
                                        ?.takeIf { adjustCrop != null }
                                        ?.let { PhotoDrag.Crop(it, currentCrop) }
                                    ?: if (markExemplar != null) PhotoDrag.Exemplar(start, start)
                                    else PhotoDrag.Pan
                            drag =
                                when (kind) {
                                    is PhotoDrag.Crop -> {
                                        val moved =
                                            kind.from.dragged(
                                                kind.handle,
                                                (position - start) / current.scale,
                                                bounds,
                                                keep = currentExemplar,
                                                minSize = MIN_CROP_SIZE.toPx() / current.scale,
                                            )
                                        adjustCrop?.invoke(moved)
                                        kind
                                    }
                                    is PhotoDrag.Exemplar -> kind.copy(end = position)
                                    PhotoDrag.Pan -> {
                                        viewport = current.transformed(Offset.Zero, 1f, delta)
                                        kind
                                    }
                                }
                        },
                    onDragEnd = {
                        val current = viewport
                        val dragged = drag
                        if (current != null && dragged is PhotoDrag.Exemplar) {
                            val a = current.toImage(dragged.start)
                            val b = current.toImage(dragged.end)
                            val limit = currentCrop
                            val box =
                                ImageBox(
                                    max(limit.left, min(a.x, b.x)),
                                    max(limit.top, min(a.y, b.y)),
                                    min(limit.right, max(a.x, b.x)),
                                    min(limit.bottom, max(a.y, b.y)),
                                )
                            if (box.width > 1 && box.height > 1) markExemplar?.invoke(box)
                        }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                    onTransform = { centroid, zoom, pan ->
                        viewport = viewport?.transformed(centroid, zoom, pan)
                    },
                )
            }
    ) {
        // Only the photo bends under the waves; frames and points stay crisp on top.
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                val current = viewport
                val source = example
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
            val cropRect = crop.inView(current)
            clipRect(
                cropRect.left,
                cropRect.top,
                cropRect.right,
                cropRect.bottom,
                ClipOp.Difference,
            ) {
                drawRect(Color.Black.copy(alpha = CROPPED_ALPHA), photoRect.topLeft, photoRect.size)
            }
            if (example != null) {
                drawCountingAnimation(
                    animation,
                    cropRect,
                    heatmap,
                    heatmap?.bounds?.inView(current),
                    pointColor,
                )
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val current = viewport ?: return@Canvas
            drawCropHandles(crop.inView(current), handleColor)
            points.forEachIndexed { index, point ->
                val color = if (point in uncertain) UNCERTAIN_COLOR else pointColor
                val center = current.toView(Offset(point.x, point.y))
                val scale = pointScale(animation, point)
                if (scale > 0) {
                    scale(scale, center) { drawPoint(center, index + 1, color, textMeasurer) }
                }
            }
            val dragged = drag
            val rect =
                when {
                    dragged is PhotoDrag.Exemplar -> {
                        val (start, end) = dragged
                        Rect(
                            Offset(min(start.x, end.x), min(start.y, end.y)),
                            Size(abs(end.x - start.x), abs(end.y - start.y)),
                        )
                    }
                    exemplar != null -> exemplar.inView(current)
                    else -> null
                }
            if (rect != null) drawExemplarFrame(rect)
        }
    }
}

/** A thin frame around the crop, with a bracket at each corner and a bar in each edge's middle. */
private fun DrawScope.drawCropHandles(crop: Rect, color: Color) {
    drawRect(Color.White.copy(alpha = 0.8f), crop.topLeft, crop.size, style = Stroke(1.dp.toPx()))
    val length = min(HANDLE_LENGTH.toPx(), min(crop.width, crop.height) / 3)
    val stroke = HANDLE_STROKE.toPx()
    fun line(from: Offset, to: Offset) = drawLine(color, from, to, stroke, StrokeCap.Round)
    for (corner in listOf(crop.topLeft, crop.topRight, crop.bottomLeft, crop.bottomRight)) {
        val inward = crop.center - corner
        line(corner, corner + Offset(if (inward.x > 0) length else -length, 0f))
        line(corner, corner + Offset(0f, if (inward.y > 0) length else -length))
    }
    val half = length / 2
    for (middle in listOf(crop.topCenter, crop.bottomCenter)) {
        line(middle - Offset(half, 0f), middle + Offset(half, 0f))
    }
    for (middle in listOf(crop.centerLeft, crop.centerRight)) {
        line(middle - Offset(0f, half), middle + Offset(0f, half))
    }
}

/** A thin rounded frame with bolder rounded corners, like a camera's focus frame. */
private fun DrawScope.drawExemplarFrame(rect: Rect) {
    val scale = min(1f, min(rect.width, rect.height) / EXEMPLAR_FULL_SIZE.toPx())
    val radius = EXEMPLAR_RADIUS.toPx() * scale
    val length = EXEMPLAR_CORNER_LENGTH.toPx() * scale
    val corners = Path()
    fun corner(x: Float, y: Float, dx: Float, dy: Float, startAngle: Float) {
        // dx and dy point from the corner into the rect; the arc sweeps from the vertical to the
        // horizontal edge.
        corners.moveTo(x, y + dy * length)
        corners.lineTo(x, y + dy * radius)
        corners.arcTo(
            Rect(
                Offset(min(x, x + 2 * dx * radius), min(y, y + 2 * dy * radius)),
                Size(2 * radius, 2 * radius),
            ),
            startAngle,
            if (dx * dy > 0) 90f else -90f,
            forceMoveTo = false,
        )
        corners.lineTo(x + dx * length, y)
    }
    corner(rect.left, rect.top, 1f, 1f, 180f)
    corner(rect.right, rect.top, -1f, 1f, 0f)
    corner(rect.right, rect.bottom, -1f, -1f, 0f)
    corner(rect.left, rect.bottom, 1f, -1f, 180f)
    val frame = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
    val outline = EXEMPLAR_OUTLINE.toPx() * scale
    val cornerStroke = EXEMPLAR_CORNER_STROKE.toPx() * scale
    val halo = EXEMPLAR_HALO.toPx() * scale
    fun stroke(color: Color, extra: Float) {
        drawPath(frame, color, style = Stroke(outline + extra))
        drawPath(corners, color, style = Stroke(cornerStroke + extra, cap = StrokeCap.Round))
    }
    stroke(Color.Black.copy(alpha = 0.3f), halo)
    stroke(Color.White, 0f)
}

/** Where a box in image pixels lies in the view. */
private fun ImageBox.inView(viewport: Viewport) =
    Rect(viewport.toView(Offset(left, top)), viewport.toView(Offset(right, bottom)))

/** A see-through dot with its number, which stays readable over any photo. */
private fun DrawScope.drawPoint(
    center: Offset,
    number: Int,
    color: Color,
    textMeasurer: TextMeasurer,
) {
    drawCircle(color.copy(alpha = POINT_ALPHA), POINT_RADIUS.toPx(), center)
    drawCircle(
        Color.White.copy(alpha = 0.8f),
        POINT_RADIUS.toPx(),
        center,
        style = Stroke(POINT_OUTLINE.toPx()),
    )
    val text =
        textMeasurer.measure(
            number.toString(),
            TextStyle(
                color = Color.White,
                fontSize = POINT_NUMBER_SIZE,
                fontWeight = FontWeight.Bold,
                shadow = Shadow(Color.Black, blurRadius = 3f),
            ),
        )
    drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
}

/**
 * One finger taps or drags (from `start`, now at `position`, moved by `delta` since the last call);
 * two fingers zoom and pan. A second finger cancels a drag.
 */
private suspend fun PointerInputScope.detectPhotoGestures(
    onTap: (Offset) -> Unit,
    onDrag: (start: Offset, position: Offset, delta: Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onTransform: (centroid: Offset, zoom: Float, pan: Offset) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    var dragging = false
    var transforming = false
    do {
        val event = awaitPointerEvent()
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
    when {
        transforming -> {}
        dragging -> onDragEnd()
        else -> onTap(down.position)
    }
}
