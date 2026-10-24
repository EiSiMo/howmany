package run.moritz.howmany

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Space Grotesk, for what the app is about: the count, the points' numbers and the question what to
 * count. Everything else stays in the system's font, so the app still feels like part of it.
 */
val DisplayFont =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
            Font(
                R.font.space_grotesk,
                it,
                variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)),
            )
        }
    )

/**
 * The dark Material scheme of the launcher icon's accent, for Android before 12, which has no
 * system colors.
 */
private val FixedDarkColorScheme =
    darkColorScheme(
        primary = ComposeColor(0xFFB4C5FF),
        onPrimary = ComposeColor(0xFF1A2E60),
        primaryContainer = ComposeColor(0xFF324478),
        onPrimaryContainer = ComposeColor(0xFFDBE1FF),
        inversePrimary = ComposeColor(0xFF4A5C92),
        secondary = ComposeColor(0xFFC1C6DD),
        onSecondary = ComposeColor(0xFF2A3042),
        secondaryContainer = ComposeColor(0xFF414659),
        onSecondaryContainer = ComposeColor(0xFFDDE1F9),
        tertiary = ComposeColor(0xFFE2BBDC),
        onTertiary = ComposeColor(0xFF422741),
        tertiaryContainer = ComposeColor(0xFF5A3D58),
        onTertiaryContainer = ComposeColor(0xFFFFD6F8),
        background = ComposeColor(0xFF121318),
        onBackground = ComposeColor(0xFFE3E2E9),
        surface = ComposeColor(0xFF121318),
        onSurface = ComposeColor(0xFFE3E2E9),
        surfaceVariant = ComposeColor(0xFF45464F),
        onSurfaceVariant = ComposeColor(0xFFC5C6D0),
        inverseSurface = ComposeColor(0xFFE3E2E9),
        inverseOnSurface = ComposeColor(0xFF2F3036),
        outline = ComposeColor(0xFF8F909A),
        outlineVariant = ComposeColor(0xFF45464F),
        surfaceBright = ComposeColor(0xFF38393F),
        surfaceDim = ComposeColor(0xFF121318),
        surfaceContainerLowest = ComposeColor(0xFF0D0E13),
        surfaceContainerLow = ComposeColor(0xFF1A1B21),
        surfaceContainer = ComposeColor(0xFF1E1F25),
        surfaceContainerHigh = ComposeColor(0xFF292A2F),
        surfaceContainerHighest = ComposeColor(0xFF33343A),
    )

/**
 * Always dark, like a camera, so the photo is the hero, and in the system's accent color, which
 * ties the controls to the counted points.
 */
@Composable
fun HowManyTheme(content: @Composable () -> Unit) {
    val typography = Typography()
    MaterialTheme(
        colorScheme =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(LocalContext.current)
            } else {
                FixedDarkColorScheme
            },
        typography =
            typography.copy(
                displaySmall = typography.displaySmall.copy(fontFamily = DisplayFont),
                headlineMedium = typography.headlineMedium.copy(fontFamily = DisplayFont),
            ),
        content = content,
    )
}

/** Edge to edge, like a camera, with light system bars over the always dark app. */
fun ComponentActivity.enableDarkEdgeToEdge() =
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
