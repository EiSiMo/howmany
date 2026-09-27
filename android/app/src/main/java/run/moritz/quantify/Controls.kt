package run.moritz.quantify

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The controls float over the photo: dark and see-through, with a hairline edge that separates
// them from dark photos. Only the primary action and the count carry the accent.
private val SCRIM = Color(0xB81C1D20)
private val HAIRLINE = Color.White.copy(alpha = 0.18f)
private val HAIRLINE_WIDTH = 1.dp
private const val DISABLED_ALPHA = 0.38f
private const val PRESSED_SCALE = 0.9f
val SHUTTER_SIZE = 80.dp
private val SHUTTER_RING = 3.dp
private val SHUTTER_GAP = 5.dp
private val SHUTTER_ICON_SIZE = 32.dp
// While busy, the disc shrinks to this part of its size and the spinner's track shows faintly.
private const val SHUTTER_BUSY_FILL = 0.6f
private const val SHUTTER_SMALLEST_DISC = 0.6f
private const val SHUTTER_FILL_BOUNCE = 0.55f
private val SHUTTER_TRACK = Color.White.copy(alpha = 0.15f)
private val SIDE_BUTTON_SIZE = 56.dp
/** The smallest height of a [Pill], which the layout reserves for the hint. */
val PILL_HEIGHT = 36.dp
private val PILL_PADDING_HORIZONTAL = 16.dp
private val PILL_PADDING_VERTICAL = 8.dp
private val PILL_ICON_SIZE = 18.dp
private val PILL_ICON_GAP = 8.dp
private val PILL_TEXT = Color.White.copy(alpha = 0.92f)
private val COUNT_PADDING = 28.dp

/** A dark, see-through surface of [shape] with a hairline edge, floating over the photo. */
private fun Modifier.floating(shape: Shape = CircleShape) =
    clip(shape).background(SCRIM).border(HAIRLINE_WIDTH, HAIRLINE, shape)

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
    val pressed by interaction.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            if (pressed) PRESSED_SCALE else 1f,
            spring(stiffness = Spring.StiffnessMedium),
        )
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

/** The number of counted objects, large and crisp, in the shutter's place and height. */
@Composable
fun CountChip(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.height(SHUTTER_SIZE).floating().padding(horizontal = COUNT_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}
