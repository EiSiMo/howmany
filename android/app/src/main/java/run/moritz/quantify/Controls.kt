package run.moritz.quantify

import android.content.ClipData
import android.icu.text.NumberFormat
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.semantics.onClick
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
private val SHUTTER_ICON_SIZE = 32.dp
// While busy, the disc shrinks to this part of its size and the spinner's track shows faintly.
private const val SHUTTER_BUSY_FILL = 0.6f
private const val SHUTTER_SMALLEST_DISC = 0.6f
private const val SHUTTER_FILL_BOUNCE = 0.55f
private val SHUTTER_TRACK = Color.White.copy(alpha = 0.15f)
private val SIDE_BUTTON_SIZE = 56.dp
/** The smallest height of a [Pill], which the controls reserve for the hint. */
private val PILL_HEIGHT = 36.dp
private val PILL_PADDING_HORIZONTAL = 16.dp
private val PILL_PADDING_VERTICAL = 8.dp
private val PILL_ICON_SIZE = 18.dp
private val PILL_ICON_GAP = 8.dp
private val PILL_TEXT = Color.White.copy(alpha = 0.92f)
private val COUNT_PADDING = 28.dp

// The controls float in the thumb zone: a hint above the shutter, which sits this high.
val CONTROLS_BOTTOM = 20.dp
val HINT_GAP = 12.dp
/** How much of the screen's bottom the controls take. */
val CONTROLS_HEIGHT = CONTROLS_BOTTOM + SHUTTER_SIZE + HINT_GAP + PILL_HEIGHT + HINT_GAP
val HINT_PADDING = 24.dp
private const val SIDE_BUTTON_BIAS = 0.74f
// The shutter and the count grow in from and shrink to this part of their size.
private const val SWAP_SCALE = 0.8f

/** A dark, see-through surface of [shape] with a hairline edge, floating over the photo. */
private fun Modifier.floating(shape: Shape = CircleShape) =
    clip(shape).background(SCRIM).border(HAIRLINE_WIDTH, HAIRLINE, shape)

/**
 * The hint at what to do next, or at what went wrong, above the shutter to count, which turns into
 * the count once there is one, between the buttons to pick another photo and to clear the exemplar.
 * The count shows [revealed] as it rises towards [count], which a tap copies.
 */
@Composable
fun Controls(
    state: CountState,
    count: Int?,
    revealed: () -> Int,
    onPickPhoto: () -> Unit,
    onCount: () -> Unit,
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
            if (hint == R.string.donate) {
                // Donating corrections as training data is not built yet.
                Pill(
                    stringResource(hint),
                    icon = painterResource(R.drawable.ic_donate),
                    onClick = {},
                )
            } else {
                Pill(stringResource(hint))
            }
        }
        Spacer(Modifier.height(HINT_GAP))
        // The side buttons stay put while the shutter turns into the wider count.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RoundButton(
                painterResource(R.drawable.ic_pick_photo),
                stringResource(R.string.pick_photo),
                onPickPhoto,
                Modifier.align(BiasAlignment(-SIDE_BUTTON_BIAS, 0f)),
            )
            AnimatedContent(
                count != null,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = SWAP_SCALE)) togetherWith
                        (fadeOut() + scaleOut(targetScale = SWAP_SCALE))
                },
                contentAlignment = Alignment.Center,
                label = "shutter",
            ) { counted ->
                if (counted) {
                    // A cleared count fades out with nothing left to copy.
                    CountChip(revealed(), count ?: 0)
                } else {
                    Shutter(
                        painterResource(R.drawable.ic_mark),
                        stringResource(R.string.count),
                        onCount,
                        enabled = phase != CountPhase.Marking,
                        busy = phase == CountPhase.Counting,
                    )
                }
            }
            RoundButton(
                painterResource(R.drawable.ic_clear),
                stringResource(R.string.clear),
                onClear,
                Modifier.align(BiasAlignment(SIDE_BUTTON_BIAS, 0f)),
                enabled = phase != CountPhase.Marking,
            )
        }
    }
}

/** What to tell the user in [state]: what to do next, or what went wrong. */
@StringRes
fun hint(state: CountState): Int =
    state.error?.message
        ?: when (state.phase) {
            CountPhase.Empty -> R.string.empty_text
            CountPhase.Marking -> R.string.mark_exemplar
            CountPhase.Ready -> R.string.adjust_crop
            CountPhase.Counting -> R.string.counting
            CountPhase.Counted -> if (state.corrected) R.string.donate else R.string.correct
        }

/**
 * The primary action, like a camera's shutter: a white ring around an accent disc with [icon].
 * Without [enabled] only a dim ring is left; while [busy] a spinner runs around it.
 */
@Composable
fun Shutter(
    icon: Painter,
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
    // The disc grows in with a little bounce when there is something to do.
    val fill by
        animateFloatAsState(
            if (enabled && !busy) 1f else if (busy) SHUTTER_BUSY_FILL else 0f,
            spring(dampingRatio = SHUTTER_FILL_BOUNCE, stiffness = Spring.StiffnessMediumLow),
        )
    val iconColor by
        animateColorAsState(
            if (enabled && !busy) onAccent else Color.White.copy(alpha = DISABLED_ALPHA)
        )
    Box(
        modifier
            .size(SHUTTER_SIZE)
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
            val outer = size.minDimension / 2
            drawCircle(SCRIM, outer)
            if (!busy) {
                drawCircle(
                    Color.White.copy(alpha = DISABLED_ALPHA + (1 - DISABLED_ALPHA) * fill),
                    outer - ring / 2,
                    style = Stroke(ring),
                )
            }
            val disc = outer - ring - SHUTTER_GAP.toPx()
            drawCircle(
                lerp(accent.copy(alpha = 0f), accent, fill),
                disc * (SHUTTER_SMALLEST_DISC + (1 - SHUTTER_SMALLEST_DISC) * fill),
            )
        }
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(SHUTTER_SIZE),
                color = accent,
                strokeWidth = SHUTTER_RING,
                trackColor = SHUTTER_TRACK,
            )
        }
        Icon(
            icon,
            contentDescription,
            tint = iconColor,
            modifier = Modifier.size(SHUTTER_ICON_SIZE),
        )
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

/** A short floating text, tappable when [onClick] is given, with an accent [icon] in front. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .heightIn(min = PILL_HEIGHT)
            .floating()
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = PILL_PADDING_HORIZONTAL, vertical = PILL_PADDING_VERTICAL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PILL_ICON_GAP),
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(PILL_ICON_SIZE),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = PILL_TEXT,
            textAlign = TextAlign.Center,
        )
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
 * that many objects. It shows [revealed] as the count rises, and a tap copies the whole [count] as
 * plain digits, which paste cleanly into spreadsheets.
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
            .clickable(interactionSource = interaction, indication = null, onClick = copy)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(copyLabel) {
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
