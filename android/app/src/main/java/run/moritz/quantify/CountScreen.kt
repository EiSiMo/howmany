package run.moritz.quantify

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
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
import run.moritz.quantify.counting.Point

// Points keep their size on screen at any zoom; taps within the hit radius hit them.
// Points are see-through, so the object underneath stays visible while correcting.
private val POINT_RADIUS = 10.dp
private val POINT_OUTLINE = 1.5.dp
private const val POINT_ALPHA = 0.45f
private val POINT_NUMBER_SIZE = 9.sp
private val HIT_RADIUS = 24.dp
private val EXEMPLAR_STROKE = 3.dp

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
    val points = state.points

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (points != null) Count(points.size)
                    else Text(stringResource(R.string.app_name))
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (photo == null) {
                EmptyState(pickPhoto, Modifier.align(Alignment.Center))
            } else {
                Photo(
                    photo = photo,
                    exemplar = state.exemplar.takeIf { points == null },
                    points = points.orEmpty(),
                    onMarkExemplar =
                        viewModel::markExemplar.takeIf { points == null && !state.counting },
                    onTap = viewModel::toggle.takeIf { points != null },
                    modifier =
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                )
                val hint =
                    when {
                        points != null -> R.string.correct
                        state.exemplar == null -> R.string.mark_example
                        else -> null
                    }
                if (hint != null) Hint(stringResource(hint), Modifier.align(Alignment.TopCenter))
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

/** A short instruction floating over the photo. */
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.padding(12.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Shows the photo with the example or the counted points. Two fingers zoom and pan. One finger
 * drags a box around one object while [onMarkExemplar] is given, and pans otherwise. Taps go to
 * [onTap], with a hit radius in image pixels.
 */
@Composable
private fun Photo(
    photo: Bitmap,
    exemplar: ImageBox?,
    points: List<Point>,
    onMarkExemplar: ((ImageBox) -> Unit)?,
    onTap: ((at: Point, hitRadius: Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val image = remember(photo) { photo.asImageBitmap() }
    var viewport by remember(photo) { mutableStateOf<Viewport?>(null) }
    var drag by remember(photo) { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val markExemplar by rememberUpdatedState(onMarkExemplar)
    val tap by rememberUpdatedState(onTap)
    val exemplarColor = MaterialTheme.colorScheme.tertiary
    val pointColor = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { size ->
                viewport =
                    Viewport.fit(size.toSize(), Size(photo.width.toFloat(), photo.height.toFloat()))
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
                    onDrag = { start, position, delta ->
                        if (markExemplar != null) drag = start to position
                        else viewport = viewport?.transformed(Offset.Zero, 1f, delta)
                    },
                    onDragEnd = {
                        val current = viewport
                        val dragged = drag
                        if (current != null && dragged != null) {
                            val a = current.toImage(dragged.first)
                            val b = current.toImage(dragged.second)
                            val box =
                                ImageBox(
                                    max(0f, min(a.x, b.x)),
                                    max(0f, min(a.y, b.y)),
                                    min(photo.width.toFloat(), max(a.x, b.x)),
                                    min(photo.height.toFloat(), max(a.y, b.y)),
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
        val current = viewport ?: return@Canvas
        val topLeft = current.toView(Offset.Zero)
        val bottomRight = current.toView(Offset(photo.width.toFloat(), photo.height.toFloat()))
        drawImage(
            image,
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize =
                IntSize(
                    (bottomRight.x - topLeft.x).roundToInt(),
                    (bottomRight.y - topLeft.y).roundToInt(),
                ),
        )
        points.forEachIndexed { index, point ->
            drawPoint(current.toView(Offset(point.x, point.y)), index + 1, pointColor, textMeasurer)
        }
        val dragged = drag
        val rect =
            when {
                dragged != null -> {
                    val (start, end) = dragged
                    Rect(
                        Offset(min(start.x, end.x), min(start.y, end.y)),
                        Size(abs(end.x - start.x), abs(end.y - start.y)),
                    )
                }
                exemplar != null ->
                    Rect(
                        current.toView(Offset(exemplar.left, exemplar.top)),
                        current.toView(Offset(exemplar.right, exemplar.bottom)),
                    )
                else -> null
            }
        if (rect != null) {
            drawRect(exemplarColor, rect.topLeft, rect.size, style = Stroke(EXEMPLAR_STROKE.toPx()))
        }
    }
}

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
