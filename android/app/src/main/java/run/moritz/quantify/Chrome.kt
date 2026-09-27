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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The controls float over the photo: dark and see-through, with a hairline edge that separates
// them from dark photos. Only the primary action and the count carry the accent.
private val SCRIM = Color(0xB81C1D20)
private val HAIRLINE = Color.White.copy(alpha = 0.18f)
private const val DISABLED_ALPHA = 0.38f
val SHUTTER_SIZE = 80.dp
private val SHUTTER_RING = 3.dp
private val SHUTTER_GAP = 5.dp
private val SIDE_BUTTON_SIZE = 56.dp
private const val PRESSED_SCALE = 0.9f

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
            if (enabled && !busy) 1f else if (busy) 0.6f else 0f,
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
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
            drawCircle(lerp(accent.copy(alpha = 0f), accent, fill), disc * (0.6f + 0.4f * fill))
        }
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(SHUTTER_SIZE),
                color = accent,
                strokeWidth = SHUTTER_RING,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
        }
        Icon(icon, contentDescription, tint = iconColor, modifier = Modifier.size(32.dp))
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
            .clip(CircleShape)
            .background(SCRIM)
            .border(1.dp, HAIRLINE, CircleShape)
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
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(SCRIM)
            .border(1.dp, HAIRLINE, CircleShape)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
        )
    }
}

/** The number of counted objects, large and crisp, in the shutter's place and height. */
@Composable
fun CountChip(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(SHUTTER_SIZE)
            .clip(CircleShape)
            .background(SCRIM)
            .border(1.dp, HAIRLINE, CircleShape)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                pluralStringResource(R.plurals.objects, count),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
