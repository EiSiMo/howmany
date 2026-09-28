package run.moritz.howmany

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Room around the photo, so its edges can be grabbed from outside too.
private val PHOTO_MARGIN = 24.dp
// The status bar's pull-down cannot be excluded, so the top edge's grab zone stays a margin away.
private val PHOTO_MARGIN_TOP = HANDLE_REACH + PHOTO_MARGIN
private val SCRIM_TOP = Color.Black.copy(alpha = 0.5f)
private val SCRIM_BOTTOM = Color.Black.copy(alpha = 0.6f)

/**
 * The one screen: taking or picking a photo, marking an exemplar, counting and correcting the
 * count, with the photo behind the controls.
 */
@Composable
fun CountScreen(viewModel: CountViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sources = rememberPhotoSources(viewModel::pickPhoto, viewModel::noCamera)
    val photo = state.photo
    val crop = state.crop
    val animation = rememberCountingAnimation(state)
    // A cleared count's points stay until they have hidden.
    val shown = animation.shown(state)
    val points = shown.counted
    // The count rises with the points the reveal has shown so far.
    val revealed by
        remember(points) { derivedStateOf { points?.count { animation.pointScale(it) > 0 } ?: 0 } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (photo == null || crop == null) {
            EmptyState(sources, hint(state))
            return@Box
        }
        // The photo fills the screen behind the system bars and the controls, fitted between
        // them until the user zooms.
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing
        val margin =
            with(density) {
                Margin(
                    left = insets.getLeft(this, direction) + PHOTO_MARGIN.toPx(),
                    top = insets.getTop(this) + PHOTO_MARGIN_TOP.toPx(),
                    right = insets.getRight(this, direction) + PHOTO_MARGIN.toPx(),
                    bottom = insets.getBottom(this) + CONTROLS_HEIGHT.toPx(),
                )
            }
        val phase = state.phase
        Photo(
            photo = photo,
            crop = crop,
            countedArea = state.countedArea,
            margin = margin,
            exemplarFrame = state.exemplar.takeIf { points == null },
            points = points.orEmpty(),
            uncertain = shown.uncertain,
            exemplar = state.exemplar,
            heatmap = state.heatmap,
            counting = phase == CountPhase.Counting,
            animation = animation,
            onAdjustCrop = viewModel::adjustCrop,
            onMarkExemplar =
                viewModel::markExemplar.takeIf {
                    phase == CountPhase.Marking || phase == CountPhase.Ready
                },
            onTap = viewModel::toggle.takeIf { phase == CountPhase.Counted },
            zoomOnDoubleTap = phase == CountPhase.Marking || phase == CountPhase.Ready,
            modifier = Modifier.fillMaxSize(),
        )
        // Scrims keep the status bar and the controls readable on bright photos.
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(SCRIM_TOP, Color.Transparent)))
                .statusBarsPadding()
                .height(PHOTO_MARGIN_TOP)
        )
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, SCRIM_BOTTOM)))
                .navigationBarsPadding()
                .height(CONTROLS_HEIGHT + PHOTO_MARGIN)
        )
        Controls(
            state = state,
            count = points?.size,
            revealed = { revealed },
            sources = sources,
            onCount = viewModel::count,
            onClear = {
                animation.hide(state)
                viewModel.clear()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
