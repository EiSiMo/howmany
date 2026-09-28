package run.moritz.howmany

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
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
 * Always dark, like a camera, so the photo is the hero, and in the system's accent color, which
 * ties the controls to the counted points.
 */
@Composable
fun HowManyTheme(content: @Composable () -> Unit) {
    val typography = Typography()
    MaterialTheme(
        colorScheme = dynamicDarkColorScheme(LocalContext.current),
        typography =
            typography.copy(
                displaySmall = typography.displaySmall.copy(fontFamily = DisplayFont),
                headlineMedium = typography.headlineMedium.copy(fontFamily = DisplayFont),
            ),
        content = content,
    )
}
