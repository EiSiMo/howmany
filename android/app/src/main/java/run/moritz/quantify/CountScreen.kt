package run.moritz.quantify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import kotlin.math.min
import run.moritz.quantify.counting.Box as ImageBox

private val EXEMPLAR_COLOR = Color(0xFFFFC107)
// Consecutive detections get hues a golden angle apart, so neighbours stand out from each other.
private const val GOLDEN_ANGLE = 137.508f
private const val DETECTION_FILL_ALPHA = 0.35f
private val MIN_NUMBER_SIZE = 8.sp
private val MAX_NUMBER_SIZE = 14.sp

@Composable
fun CountScreen(viewModel: CountViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            uri?.let(viewModel::pickPhoto)
        }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                ) {
                    Text(stringResource(R.string.pick_photo))
                }
                Button(
                    onClick = viewModel::count,
                    enabled = state.exemplar != null && !state.counting,
                ) {
                    Text(stringResource(R.string.count))
                }
            }
            val detections = state.detections
            val duration = state.duration
            val status =
                when {
                    state.counting -> stringResource(R.string.counting)
                    detections != null && duration != null ->
                        pluralStringResource(
                            R.plurals.result,
                            detections.size,
                            detections.size,
                            duration.inWholeMilliseconds / 1000f,
                        )
                    state.photo != null -> stringResource(R.string.mark_example)
                    else -> ""
                }
            Text(status, style = MaterialTheme.typography.titleMedium)
            state.photo?.let { photo ->
                PhotoWithBoxes(
                    photo = photo,
                    exemplar = state.exemplar,
                    detections = detections.orEmpty(),
                    onMarkExemplar = viewModel::markExemplar,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/** Shows the photo with the detections, and lets the user drag a box around one object. */
@Composable
private fun PhotoWithBoxes(
    photo: android.graphics.Bitmap,
    exemplar: ImageBox?,
    detections: List<ImageBox>,
    onMarkExemplar: (ImageBox) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val viewWidth = with(density) { maxWidth.toPx() }
        val viewHeight = with(density) { maxHeight.toPx() }
        // How the photo is fitted into the view: view = image * scale + offset.
        val scale = min(viewWidth / photo.width, viewHeight / photo.height)
        val offset =
            Offset((viewWidth - photo.width * scale) / 2, (viewHeight - photo.height * scale) / 2)
        fun toImage(point: Offset) = (point - offset) / scale
        var drag by remember(photo) { mutableStateOf<Pair<Offset, Offset>?>(null) }
        val textMeasurer = rememberTextMeasurer()

        Image(
            photo.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            Modifier.fillMaxSize().pointerInput(photo) {
                detectDragGestures(
                    onDragStart = { drag = it to it },
                    onDrag = { change, _ -> drag = drag?.copy(second = change.position) },
                    onDragEnd = {
                        drag?.let { (start, end) ->
                            val a = toImage(start)
                            val b = toImage(end)
                            val box =
                                ImageBox(
                                    max(0f, min(a.x, b.x)),
                                    max(0f, min(a.y, b.y)),
                                    min(photo.width.toFloat(), max(a.x, b.x)),
                                    min(photo.height.toFloat(), max(a.y, b.y)),
                                )
                            if (box.width > 1 && box.height > 1) onMarkExemplar(box)
                        }
                        drag = null
                    },
                )
            }
        ) {
            detections.forEachIndexed { index, box ->
                drawDetection(box.inView(scale, offset), index + 1, textMeasurer)
            }
            val dragged = drag
            if (dragged != null) {
                val (start, end) = dragged
                drawRect(
                    EXEMPLAR_COLOR,
                    Offset(min(start.x, end.x), min(start.y, end.y)),
                    Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
                    style = Stroke(4f),
                )
            } else if (exemplar != null) {
                val rect = exemplar.inView(scale, offset)
                drawRect(EXEMPLAR_COLOR, rect.topLeft, rect.size, style = Stroke(4f))
            }
        }
    }
}

/** Fills the detection with its own color and writes its number in the middle. */
private fun DrawScope.drawDetection(rect: Rect, number: Int, textMeasurer: TextMeasurer) {
    val color = Color.hsv((number - 1) * GOLDEN_ANGLE % 360, 0.8f, 1f)
    drawRect(color.copy(alpha = DETECTION_FILL_ALPHA), rect.topLeft, rect.size)
    drawRect(color, rect.topLeft, rect.size, style = Stroke(2f))
    val fontSize = (rect.minDimension / 2).coerceIn(MIN_NUMBER_SIZE.toPx(), MAX_NUMBER_SIZE.toPx())
    val text =
        textMeasurer.measure(
            number.toString(),
            TextStyle(
                color = Color.White,
                fontSize = fontSize.toSp(),
                fontWeight = FontWeight.Bold,
                shadow = Shadow(Color.Black, blurRadius = 4f),
            ),
        )
    drawText(text, topLeft = rect.center - Offset(text.size.width / 2f, text.size.height / 2f))
}

/** Where a box in image pixels lies in the view. */
private fun ImageBox.inView(scale: Float, offset: Offset) =
    Rect(Offset(left * scale, top * scale) + offset, Size(width * scale, height * scale))
