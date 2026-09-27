package run.moritz.quantify

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The mark glows faintly from a bit above the middle of the screen, where the title sits.
private const val GLOW_ALPHA = 0.16f
private const val GLOW_CENTER = 0.4f
private const val GLOW_RADIUS = 0.75f
private const val TITLE_BIAS = -0.2f
private val TITLE_PADDING = 40.dp
private val MARK_SIZE = 96.dp
private val MARK_GAP = 24.dp
private val APP_NAME_SPACING = 4.sp
private val APP_NAME_GAP = 12.dp

/**
 * The first screen: the mark glowing on black, what the app does, and the shutter to pick a photo
 * where the count button will be, with the [hint] above it.
 */
@Composable
fun EmptyState(onPickPhoto: () -> Unit, @StringRes hint: Int) {
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = GLOW_ALPHA)
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(glow, Color.Transparent),
                    center = Offset(size.width / 2, size.height * GLOW_CENTER),
                    radius = size.width * GLOW_RADIUS,
                )
            )
        }
    ) {
        Column(
            Modifier.align(BiasAlignment(0f, TITLE_BIAS)).padding(horizontal = TITLE_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painterResource(R.drawable.ic_mark),
                contentDescription = null,
                modifier = Modifier.size(MARK_SIZE),
            )
            Spacer(Modifier.height(MARK_GAP))
            Text(
                stringResource(R.string.app_name).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = APP_NAME_SPACING,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(APP_NAME_GAP))
            Text(
                stringResource(R.string.empty_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = CONTROLS_BOTTOM),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pill(stringResource(hint), Modifier.padding(horizontal = HINT_PADDING))
            Spacer(Modifier.height(HINT_GAP))
            Shutter(
                painterResource(R.drawable.ic_pick_photo),
                stringResource(R.string.pick_photo),
                onPickPhoto,
            )
        }
    }
}
