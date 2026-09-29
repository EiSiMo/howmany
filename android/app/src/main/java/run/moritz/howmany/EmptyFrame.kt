package run.moritz.howmany

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The empty frame is the crop's frame with slightly dimmed handles, so it reads as waiting for a
// photo rather than as something to drag.
private val FRAME_COLOR = Color.White.copy(alpha = 0.7f)
private val TITLE_PADDING = 32.dp

/**
 * Where the photo will be, before there is one: the crop's frame inside [margin], filled with a
 * surface that sets it off from the background, and the question what to count, unless a photo is
 * [loading] already. A tap on it calls [onPickPhoto].
 */
@Composable
fun EmptyFrame(
    margin: Margin,
    loading: Boolean,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = MaterialTheme.colorScheme.surfaceContainer
    val density = LocalDensity.current
    // No ripple: it would flood the whole screen.
    Box(
        modifier.clickable(
            onClickLabel = stringResource(R.string.pick_photo),
            role = Role.Button,
            interactionSource = null,
            indication = null,
            onClick = onPickPhoto,
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val frame =
                Rect(
                    margin.left,
                    margin.top,
                    size.width - margin.right,
                    size.height - margin.bottom,
                )
            drawRect(fill, frame.topLeft, frame.size)
            drawCropHandles(frame, FRAME_COLOR)
        }
        if (!loading)
            Text(
                stringResource(R.string.empty_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier =
                    with(density) {
                            Modifier.padding(
                                start = margin.left.toDp(),
                                top = margin.top.toDp(),
                                end = margin.right.toDp(),
                                bottom = margin.bottom.toDp(),
                            )
                        }
                        .padding(horizontal = TITLE_PADDING)
                        .align(Alignment.Center),
            )
    }
}
