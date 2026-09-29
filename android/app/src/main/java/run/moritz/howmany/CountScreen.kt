package run.moritz.howmany

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Room around the photo, so its edges can be grabbed from outside too.
private val PHOTO_MARGIN = 24.dp
// The status bar's pull-down cannot be excluded, so the top edge's grab zone stays a margin away.
private val PHOTO_MARGIN_TOP = HANDLE_REACH + PHOTO_MARGIN
private val SCRIM_TOP = Color.Black.copy(alpha = 0.5f)
private val SCRIM_BOTTOM = Color.Black.copy(alpha = 0.6f)
// The about button stays in the background of the empty screen, yet clearly not disabled.
private val ABOUT_ICON = Color.White.copy(alpha = 0.7f)

/**
 * The one screen: taking or picking a photo, marking an exemplar, counting and correcting the
 * count, with the photo behind the controls.
 */
@Composable
fun CountScreen(viewModel: CountViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sources = rememberPhotoSources(viewModel::pickPhoto, viewModel::noCamera)
    val export = rememberExport(viewModel::exportFailed)
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
        // The photo fills the screen behind the system bars and the controls, fitted between
        // them until the user zooms; until there is one, an empty frame waits there.
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
        if (photo == null || crop == null) {
            EmptyFrame(margin, state.loadingPhoto, sources.pickPhoto, Modifier.fillMaxSize())
        } else {
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
        }
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
        // What the app is and builds on, only while there is no photo whose frame takes the top.
        AnimatedVisibility(
            photo == null,
            Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)
                )
                .padding(end = TOP_BUTTON_EDGE),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val context = LocalContext.current
            IconButton(
                onClick = { context.startActivity(Intent(context, AboutActivity::class.java)) }
            ) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    stringResource(R.string.about),
                    tint = ABOUT_ICON,
                )
            }
        }
        Controls(
            state = state,
            count = points?.size,
            revealed = { revealed },
            sources = sources,
            onCount = viewModel::count,
            onExport = { export(state) },
            onClear = {
                animation.hide(state)
                viewModel.clear()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
