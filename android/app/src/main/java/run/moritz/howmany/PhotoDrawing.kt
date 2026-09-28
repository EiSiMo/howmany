package run.moritz.howmany

import android.icu.text.NumberFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.min
import run.moritz.howmany.counting.Box as ImageBox

// The crop is a thin rounded white frame with accent handles.
private val CROP_RADIUS = 12.dp
private val CROP_OUTLINE = 1.dp
private val CROP_OUTLINE_COLOR = Color.White.copy(alpha = 0.8f)
private val HANDLE_LENGTH = 20.dp
private val HANDLE_STROKE = 4.dp
// The exemplar frame is white with a soft dark halo, so it reads on any photo without a theme tint.
// Frames smaller than the full-size frame shrink as a whole, so the corners never merge.
private val EXEMPLAR_FULL_SIZE = 72.dp
private val EXEMPLAR_OUTLINE = 1.5.dp
private val EXEMPLAR_CORNER_STROKE = 3.5.dp
private val EXEMPLAR_CORNER_LENGTH = 16.dp
private val EXEMPLAR_RADIUS = 8.dp
private val EXEMPLAR_HALO = 2.dp
private val EXEMPLAR_HALO_COLOR = Color.Black.copy(alpha = 0.3f)
// Points keep their size on screen at any zoom. They are see-through, so the object underneath
// stays visible while correcting.
private val POINT_RADIUS = 10.dp
private val POINT_OUTLINE = 1.5.dp
val POINT_OUTLINE_COLOR = Color.White.copy(alpha = 0.8f)
const val POINT_ALPHA = 0.45f
private val POINT_NUMBER_SIZE = 9.sp
private const val POINT_NUMBER_SHADOW_BLUR = 3f
// Material has no warning color; yellow stands out from the theme's points on any photo.
val UNCERTAIN_COLOR = Color(0xFFFFD600)

// The handles on the crop's left and right edges, where the back gesture would steal the drag.
val SIDE_HANDLES: List<(Rect) -> Offset> =
    listOf(
        Rect::topLeft,
        Rect::centerLeft,
        Rect::bottomLeft,
        Rect::topRight,
        Rect::centerRight,
        Rect::bottomRight,
    )

/** The crop's outline in view pixels: a rounded rect, as Material shapes medium components. */
fun DrawScope.cropOutline(crop: Rect): Path =
    Path().apply { addRoundRect(RoundRect(crop, CornerRadius(cropRadius(crop)))) }

/** How round the crop's corners are; a small crop keeps room for its handles between them. */
private fun DrawScope.cropRadius(crop: Rect) =
    min(CROP_RADIUS.toPx(), min(crop.width, crop.height) / 3)

/**
 * A thin rounded frame around the crop, with a rounded bracket at each corner and a bar in each
 * edge's middle.
 */
fun DrawScope.drawCropHandles(crop: Rect, color: Color) {
    drawPath(cropOutline(crop), CROP_OUTLINE_COLOR, style = Stroke(CROP_OUTLINE.toPx()))
    val length = min(HANDLE_LENGTH.toPx(), min(crop.width, crop.height) / 3)
    val stroke = HANDLE_STROKE.toPx()
    drawPath(
        cornerBrackets(crop, cropRadius(crop), length),
        color,
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
    fun line(from: Offset, to: Offset) = drawLine(color, from, to, stroke, StrokeCap.Round)
    val half = length / 2
    for (middle in listOf(crop.topCenter, crop.bottomCenter)) {
        line(middle - Offset(half, 0f), middle + Offset(half, 0f))
    }
    for (middle in listOf(crop.centerLeft, crop.centerRight)) {
        line(middle - Offset(0f, half), middle + Offset(0f, half))
    }
}

/**
 * A bracket at each corner of [rect]: [length] along both edges, rounded with [radius] like the
 * corner of a rounded rect.
 */
private fun cornerBrackets(rect: Rect, radius: Float, length: Float): Path {
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
    return corners
}

/** A thin rounded frame with bolder rounded corners, like a camera's focus frame. */
fun DrawScope.drawExemplarFrame(rect: Rect) {
    val scale = min(1f, min(rect.width, rect.height) / EXEMPLAR_FULL_SIZE.toPx())
    val radius = EXEMPLAR_RADIUS.toPx() * scale
    val corners = cornerBrackets(rect, radius, EXEMPLAR_CORNER_LENGTH.toPx() * scale)
    val frame = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
    val outline = EXEMPLAR_OUTLINE.toPx() * scale
    val cornerStroke = EXEMPLAR_CORNER_STROKE.toPx() * scale
    val halo = EXEMPLAR_HALO.toPx() * scale
    fun stroke(color: Color, extra: Float) {
        drawPath(frame, color, style = Stroke(outline + extra))
        drawPath(corners, color, style = Stroke(cornerStroke + extra, cap = StrokeCap.Round))
    }
    stroke(EXEMPLAR_HALO_COLOR, halo)
    stroke(Color.White, 0f)
}

/** Where a box in image pixels lies in the view. */
fun ImageBox.inView(viewport: Viewport) =
    Rect(viewport.toView(Offset(left, top)), viewport.toView(Offset(right, bottom)))

/** A see-through dot with its [number], which stays readable over any photo. */
fun DrawScope.drawPoint(center: Offset, number: TextLayoutResult, color: Color) {
    drawCircle(color.copy(alpha = POINT_ALPHA), POINT_RADIUS.toPx(), center)
    drawCircle(
        POINT_OUTLINE_COLOR,
        POINT_RADIUS.toPx(),
        center,
        style = Stroke(POINT_OUTLINE.toPx()),
    )
    drawText(number, topLeft = center - Offset(number.size.width / 2f, number.size.height / 2f))
}

/**
 * The points' numbers as [drawPoint] draws them, in the user's locale, each measured only once
 * rather than for every point in every frame.
 */
class PointNumbers(private val textMeasurer: TextMeasurer, locale: Locale) {
    // Grouping would only widen the numbers in their small dots.
    private val format = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
    private val measured = mutableMapOf<Int, TextLayoutResult>()

    operator fun get(number: Int): TextLayoutResult =
        measured.getOrPut(number) {
            textMeasurer.measure(
                format.format(number),
                TextStyle(
                    color = Color.White,
                    fontSize = POINT_NUMBER_SIZE,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(Color.Black, blurRadius = POINT_NUMBER_SHADOW_BLUR),
                ),
            )
        }
}

@Composable
fun rememberPointNumbers(): PointNumbers {
    val textMeasurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    return remember(textMeasurer, locale) { PointNumbers(textMeasurer, locale) }
}
