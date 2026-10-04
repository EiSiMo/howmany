package run.moritz.howmany

import android.content.ClipData
import android.icu.text.NumberFormat
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// The controls float over the photo: dark and see-through, with a hairline edge that separates
// them from dark photos. Only the primary action and the count carry the accent.
private val SCRIM = Color(0xB81C1D20)
private val HAIRLINE = Color.White.copy(alpha = 0.18f)
private val HAIRLINE_WIDTH = 1.dp
private const val DISABLED_ALPHA = 0.38f
private const val PRESSED_SCALE = 0.9f
private val SHUTTER_SIZE = 80.dp
private val SHUTTER_RING = 3.dp
private val SHUTTER_GAP = 5.dp
// While busy, the disc shrinks to this part of its size and the spinner's track shows faintly.
private const val SHUTTER_BUSY_FILL = 0.6f
private const val SHUTTER_SMALLEST_DISC = 0.6f
private const val SHUTTER_FILL_BOUNCE = 0.55f
private val SHUTTER_TRACK = Color.White.copy(alpha = 0.15f)
private val SIDE_BUTTON_SIZE = 56.dp
private val SIDE_BUTTON_GAP = 8.dp
/** The smallest height of a [Pill], which the controls reserve for the hint. */
private val PILL_HEIGHT = 36.dp
private val PILL_PADDING_HORIZONTAL = 16.dp
private val PILL_PADDING_VERTICAL = 8.dp
private val PILL_TEXT = Color.White.copy(alpha = 0.92f)
private val COUNT_PADDING = 20.dp
// The exemplar counter in the pill: its own little field, set off from the instruction text.
private val COUNTER_GAP = 10.dp
private val COUNTER_BACKGROUND = Color.White.copy(alpha = 0.14f)
private val COUNTER_PADDING_HORIZONTAL = 8.dp
private val COUNTER_PADDING_VERTICAL = 2.dp

// The controls float in the thumb zone: a hint above the shutter, which sits this high.
private val CONTROLS_BOTTOM = 20.dp
private val HINT_GAP = 12.dp
/** How much of the screen's bottom the controls take. */
val CONTROLS_HEIGHT = CONTROLS_BOTTOM + SHUTTER_SIZE + HINT_GAP + PILL_HEIGHT + HINT_GAP
private val HINT_PADDING = 24.dp
// The side buttons sit at the screen's edges; the shutter fills the room between them.
private val SIDE_BUTTON_EDGE = 16.dp
/** The touch target of an icon button at the screen's top, above the photo. */
val TOP_BUTTON_SIZE = 48.dp
/**
 * How far an icon button at the top keeps from the screen's edge, so its icon lines up with the
 * photo's.
 */
val TOP_BUTTON_EDGE = 12.dp
// How much of the shutter's outline the spinner covers while counting.
private const val SPINNER_LENGTH = 0.2f
private const val SPINNER_PERIOD_MILLIS = 1600
// The shutter and the count grow in from and shrink to this part of their size.
private const val SWAP_SCALE = 0.8f

/** A dark, see-through surface of [shape] with a hairline edge, floating over the photo. */
private fun Modifier.floating(shape: Shape = CircleShape) =
    clip(shape).background(SCRIM).border(HAIRLINE_WIDTH, HAIRLINE, shape)

/**
 * The hint at what to do next, or at what went wrong, above the shutter to count, which turns into
 * the count once there is one. On its left are the buttons to export the count and to start over
 * with the photo, on its right those to pick or take another photo. The count shows [revealed] as
 * it rises towards [count], which a long press copies.
 */
@Composable
fun Controls(
    state: CountState,
    count: Int?,
    revealed: () -> Int,
    sources: PhotoSources,
    onCount: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = CONTROLS_BOTTOM),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            hint(state),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.padding(horizontal = HINT_PADDING),
            label = "hint",
        ) { hint ->
            Pill(stringResource(hint.text), count = hint.count)
        }
        Spacer(Modifier.height(HINT_GAP))
        // The shutter and the count fill the room between the side buttons, with the same gaps
        // around them as between the side buttons.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SIDE_BUTTON_EDGE),
            horizontalArrangement = Arrangement.spacedBy(SIDE_BUTTON_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(
                painterResource(R.drawable.ic_export),
                stringResource(R.string.export),
                onExport,
                enabled = phase == CountPhase.Counted,
            )
            RoundButton(
                painterResource(R.drawable.ic_clear),
                stringResource(R.string.clear),
                onClear,
                enabled = state.canStartOver,
            )
            AnimatedContent(
                count != null,
                Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = SWAP_SCALE)) togetherWith
                        (fadeOut() + scaleOut(targetScale = SWAP_SCALE))
                },
                contentAlignment = Alignment.Center,
                label = "shutter",
            ) { counted ->
                if (counted) {
                    // A cleared count fades out with nothing left to copy.
                    CountChip(revealed(), count ?: 0, Modifier.fillMaxWidth())
                } else {
                    Shutter(
                        stringResource(R.string.go),
                        stringResource(R.string.count),
                        onCount,
                        Modifier.fillMaxWidth(),
                        enabled = phase != CountPhase.Empty && phase != CountPhase.Marking,
                        busy = phase == CountPhase.Counting,
                    )
                }
            }
            RoundButton(
                painterResource(R.drawable.ic_pick_photo),
                stringResource(R.string.pick_photo),
                sources.pickPhoto,
            )
            RoundButton(
                painterResource(R.drawable.ic_take_photo),
                stringResource(R.string.take_photo),
                sources.takePhoto,
            )
        }
    }
}

/** What the hint pill shows: [text], and how many exemplars are marked, if that matters. */
data class CountHint(@StringRes val text: Int, val count: Int? = null)

/** What to tell the user in [state]: what to do next, or what went wrong. */
fun hint(state: CountState): CountHint =
    state.error?.message?.let { CountHint(it) }
        ?: when (state.phase) {
            CountPhase.Empty -> CountHint(R.string.empty_text)
            CountPhase.Marking,
            CountPhase.Ready -> CountHint(exemplarHint(state.exemplars.size), state.exemplars.size)
            CountPhase.Counting -> CountHint(R.string.counting)
            CountPhase.Counted -> CountHint(R.string.correct)
        }

/** How to mark exemplars with [count] of them already drawn. */
@StringRes
private fun exemplarHint(count: Int): Int =
    when {
        count == 0 -> R.string.mark_first_exemplar
        count < MAX_EXEMPLARS -> R.string.mark_more_exemplars
        else -> R.string.adjust_crop
    }

/**
 * The primary action, like a camera's shutter stretched into a pill: a white ring around an accent
 * pill with [label]. Without [enabled] only a dim ring is left; while [busy] a spinner runs around
 * it.
 */
@Composable
fun Shutter(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val interaction = remember { MutableInteractionSource() }
    val scale by pressedScale(interaction)
    // The inner pill grows in with a little bounce when there is something to do.
    val fill by
        animateFloatAsState(
            if (enabled && !busy) 1f else if (busy) SHUTTER_BUSY_FILL else 0f,
            spring(dampingRatio = SHUTTER_FILL_BOUNCE, stiffness = Spring.StiffnessMediumLow),
        )
    val labelColor by
        animateColorAsState(
            if (enabled && !busy) onAccent else Color.White.copy(alpha = DISABLED_ALPHA)
        )
    Box(
        modifier
            .height(SHUTTER_SIZE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !busy,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val ring = SHUTTER_RING.toPx()
            drawPill(SCRIM, inset = 0f)
            if (!busy) {
                drawPill(
                    Color.White.copy(alpha = DISABLED_ALPHA + (1 - DISABLED_ALPHA) * fill),
                    inset = ring / 2,
                    Stroke(ring),
                )
            }
            // The inner pill shrinks by the same amount on all sides, so it stays a pill.
            val inner = ring + SHUTTER_GAP.toPx()
            val shrink = (size.height / 2 - inner) * (1 - SHUTTER_SMALLEST_DISC) * (1 - fill)
            drawPill(lerp(accent.copy(alpha = 0f), accent, fill), inset = inner + shrink)
        }
        if (busy) PillSpinner(accent, Modifier.matchParentSize())
        Text(
            label,
            Modifier.clearAndSetSemantics { this.contentDescription = contentDescription },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = labelColor,
        )
    }
}

/** A pill filling the canvas, [inset] from its edges. */
private fun DrawScope.drawPill(color: Color, inset: Float, style: DrawStyle = Fill) {
    val pill = Size(size.width - 2 * inset, size.height - 2 * inset)
    drawRoundRect(color, Offset(inset, inset), pill, CornerRadius(pill.height / 2), style = style)
}

/** A spinner in [color] running around the ring of a pill the size of its box. */
@Composable
private fun PillSpinner(color: Color, modifier: Modifier = Modifier) {
    val phase by
        rememberInfiniteTransition(label = "spinner")
            .animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(SPINNER_PERIOD_MILLIS, easing = LinearEasing)),
                label = "phase",
            )
    Canvas(modifier) {
        val ring = SHUTTER_RING.toPx()
        val inset = ring / 2
        val outline =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        inset,
                        inset,
                        size.width - inset,
                        size.height - inset,
                        CornerRadius(size.height / 2 - inset),
                    )
                )
            }
        drawPath(outline, SHUTTER_TRACK, style = Stroke(ring))
        val measure = PathMeasure().apply { setPath(outline, forceClosed = true) }
        val start = phase * measure.length
        val end = start + SPINNER_LENGTH * measure.length
        val segment = Path()
        measure.getSegment(start, minOf(end, measure.length), segment)
        // The segment wraps around where the outline starts.
        if (end > measure.length) measure.getSegment(0f, end - measure.length, segment)
        drawPath(segment, color, style = Stroke(ring, cap = StrokeCap.Round))
    }
}

/** A secondary action beside the shutter: a small dark disc with [icon]. */
@Composable
fun RoundButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else DISABLED_ALPHA)
    Box(
        modifier
            .size(SIDE_BUTTON_SIZE)
            .graphicsLayer { this.alpha = alpha }
            .floating()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Color.White)
    }
}

/**
 * A short floating text, with the marked exemplars out of [MAX_EXEMPLARS] in their own little field
 * on the right when [count] is given.
 */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(
        modifier
            .heightIn(min = PILL_HEIGHT)
            .floating()
            .padding(horizontal = PILL_PADDING_HORIZONTAL, vertical = PILL_PADDING_VERTICAL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = PILL_TEXT,
            textAlign = TextAlign.Center,
        )
        if (count != null) {
            Spacer(Modifier.width(COUNTER_GAP))
            Box(
                Modifier.clip(RoundedCornerShape(percent = 50))
                    .background(COUNTER_BACKGROUND)
                    .padding(
                        horizontal = COUNTER_PADDING_HORIZONTAL,
                        vertical = COUNTER_PADDING_VERTICAL,
                    )
            ) {
                Text(
                    stringResource(R.string.exemplar_counter, count, MAX_EXEMPLARS),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** How large to draw a control while [interaction] presses it: a little smaller, like a button. */
@Composable
private fun pressedScale(interaction: InteractionSource): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(
        if (pressed) PRESSED_SCALE else 1f,
        spring(stiffness = Spring.StiffnessMedium),
    )
}

/**
 * The number of counted objects, large and crisp, in the shutter's place and height; read out as
 * that many objects. It shows [revealed] as the count rises, and a long press copies the whole
 * [count] as plain digits, which paste cleanly into spreadsheets.
 */
@Composable
fun CountChip(revealed: Int, count: Int, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val number = format.format(revealed)
    val description = pluralStringResource(R.plurals.counted_objects, revealed, number)
    val copyLabel = stringResource(R.string.copy_count)
    val copied = stringResource(R.string.copied)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copy = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, count.toString())))
            // From Android 13 on, the system confirms copying itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
        }
        Unit
    }
    val interaction = remember { MutableInteractionSource() }
    val scale by pressedScale(interaction)
    Box(
        modifier
            .height(SHUTTER_SIZE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .floating()
            // A long press gives haptic feedback on its own.
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = copy,
                onClick = {},
            )
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onLongClick(copyLabel) {
                    copy()
                    true
                }
            }
            .padding(horizontal = COUNT_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number,
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}
