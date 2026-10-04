package run.moritz.howmany

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.icu.text.NumberFormat
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AboutActivity"
private const val REPOSITORY = "https://github.com/EiSiMo/howmany"

// The page is a column of grouped rows, like the system's settings: each group's outer corners
// are round, the corners between its rows only slightly.
private val PAGE_PADDING = 16.dp
private val PAGE_MAX_WIDTH = 600.dp
private val GROUP_RADIUS = 24.dp
private val ROW_RADIUS = 4.dp
private val ROW_GAP = 2.dp
private val ROW_MIN_HEIGHT = 72.dp
// The licenses' list is long, so its rows are denser.
private val LICENSE_ROW_MIN_HEIGHT = 56.dp
private val LICENSE_ROW_PADDING_VERTICAL = 10.dp
private val ROW_PADDING_HORIZONTAL = 20.dp
private val ROW_PADDING_VERTICAL = 14.dp
private val ROW_CONTENT_GAP = 16.dp
private const val ROW_TEXT_ALPHA = 0.72f
private val BADGE_SIZE = 40.dp
private val BADGE_ICON_SIZE = 20.dp
private val TRAILING_ICON_SIZE = 20.dp
private val SECTION_TITLE_PADDING_TOP = 28.dp
private val SECTION_TITLE_PADDING_BOTTOM = 8.dp
// The app's mark glows in the accent color above its name.
private val HERO_GLOW_SIZE = 220.dp
private const val HERO_GLOW_ALPHA = 0.4f
private val HERO_DISC_SIZE = 112.dp
private val HERO_MARK_SIZE = 64.dp
private val VERSION_PADDING_HORIZONTAL = 12.dp
private val VERSION_PADDING_VERTICAL = 6.dp
// Tips are numbered like counted points, only larger.
private val TIP_POINT_SIZE = 32.dp
private val TIP_POINT_OUTLINE = 2.dp
private val FOOTER_PADDING = 32.dp
// The page fades out under the status bar and the back button, over this part of the scrim.
private const val SCRIM_SOLID = 0.6f

private val TIPS =
    listOf(R.string.tip_light, R.string.tip_crop, R.string.tip_exemplars, R.string.tip_correct)

/** A work the app builds on, with what it does for the app. */
private class Credit(val name: String, @StringRes val text: Int, val url: String)

private val CREDITS =
    listOf(
        Credit("GeCo2", R.string.credit_geco2, "https://github.com/jerpelhan/GECO2"),
        Credit("SAM 2", R.string.credit_sam2, "https://github.com/facebookresearch/sam2"),
        Credit("ONNX Runtime", R.string.credit_onnxruntime, "https://onnxruntime.ai"),
        Credit(
            "Space Grotesk",
            R.string.credit_space_grotesk,
            "https://github.com/floriankarsten/space-grotesk",
        ),
    )

/** What the app is, that it keeps photos private, how to count well, and what it builds on. */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDarkEdgeToEdge()
        setContent { HowManyTheme { AboutScreen(onBack = ::finish) } }
    }
}

/**
 * The about page: the app's name and version, that photos stay on the phone, tips for counting
 * well, credits for the works it builds on, links to get involved, and the licenses of all works it
 * bundles.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraries by
        produceState<List<Library>?>(null) {
            value = withContext(Dispatchers.IO) { openSourceLibraries(context) }
        }
    var licensesShown by rememberSaveable { mutableStateOf(false) }
    var shownLicense by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding =
                WindowInsets.safeDrawing
                    .add(
                        WindowInsets(
                            left = PAGE_PADDING,
                            top = TOP_BUTTON_SIZE,
                            right = PAGE_PADDING,
                            bottom = PAGE_PADDING,
                        )
                    )
                    .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Hero() }
            item { PrivateCard() }

            item { SectionTitle(R.string.tips_title) }
            itemsIndexed(TIPS) { index, tip ->
                ListRow(
                    stringResource(tip),
                    groupShape(index, TIPS.size),
                    leading = { TipPoint(index + 1) },
                )
            }

            item { SectionTitle(R.string.credits_title) }
            itemsIndexed(CREDITS) { index, credit ->
                ListRow(
                    credit.name,
                    groupShape(index, CREDITS.size),
                    text = stringResource(credit.text),
                    trailing = painterResource(R.drawable.ic_open_link),
                    onClick = { context.openLink(credit.url) },
                )
            }

            item { SectionTitle(R.string.contribute_title) }
            item {
                ListRow(
                    stringResource(R.string.source_code),
                    groupShape(0, 4),
                    text = stringResource(R.string.source_code_text),
                    leading = { IconBadge(painterResource(R.drawable.ic_source_code)) },
                    trailing = painterResource(R.drawable.ic_open_link),
                    onClick = { context.openLink(REPOSITORY) },
                )
            }
            item {
                ListRow(
                    stringResource(R.string.report_problem),
                    groupShape(1, 4),
                    text = stringResource(R.string.report_problem_text),
                    leading = { IconBadge(painterResource(R.drawable.ic_report_problem)) },
                    trailing = painterResource(R.drawable.ic_open_link),
                    onClick = { context.openLink("$REPOSITORY/issues/new") },
                )
            }
            item {
                ListRow(
                    stringResource(R.string.share_diagnostics),
                    groupShape(2, 4),
                    text = stringResource(R.string.share_diagnostics_text),
                    leading = { IconBadge(painterResource(R.drawable.ic_diagnostics)) },
                    trailing = painterResource(R.drawable.ic_open_link),
                    onClick = { scope.launch { Diagnostics.send(context) } },
                )
            }
            item {
                ListRow(
                    stringResource(R.string.contact),
                    groupShape(3, 4),
                    text = CONTACT,
                    leading = { IconBadge(painterResource(R.drawable.ic_mail)) },
                    trailing = painterResource(R.drawable.ic_open_link),
                    onClick = { context.openLink("mailto:$CONTACT") },
                )
            }

            item { Spacer(Modifier.height(SECTION_TITLE_PADDING_TOP)) }
            val shown = libraries.orEmpty().takeIf { licensesShown }.orEmpty()
            item {
                val bottomRadius by
                    animateDpAsState(if (shown.isEmpty()) GROUP_RADIUS else ROW_RADIUS)
                val rotation by animateFloatAsState(if (licensesShown) 180f else 0f)
                ListRow(
                    stringResource(R.string.licenses_title),
                    RoundedCornerShape(
                        topStart = GROUP_RADIUS,
                        topEnd = GROUP_RADIUS,
                        bottomStart = bottomRadius,
                        bottomEnd = bottomRadius,
                    ),
                    text =
                        libraries?.let {
                            pluralStringResource(R.plurals.licenses_count, it.size, it.size)
                        },
                    trailing = painterResource(R.drawable.ic_expand),
                    trailingRotation = rotation,
                    onClick = { licensesShown = !licensesShown },
                )
            }
            itemsIndexed(shown, key = { _, library -> library.uniqueId }) { index, library ->
                ListRow(
                    library.name,
                    groupShape(index + 1, shown.size + 1),
                    text = licenseSummary(library),
                    onClick = { shownLicense = library.uniqueId },
                    dense = true,
                    modifier = Modifier.animateItem(),
                )
            }

            item {
                Text(
                    stringResource(R.string.footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.page().padding(vertical = FOOTER_PADDING),
                )
            }
        }
        // The page scrolls away under the status bar and the back button.
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        SCRIM_SOLID to Color.Black,
                        1f to Color.Transparent,
                    )
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .height(TOP_BUTTON_SIZE + TOP_BUTTON_EDGE)
        )
        IconButton(
            onClick = onBack,
            Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start)
                )
                .padding(start = TOP_BUTTON_EDGE),
        ) {
            Icon(
                painterResource(R.drawable.ic_back),
                stringResource(R.string.back),
                tint = Color.White,
            )
        }
    }
    libraries
        ?.find { it.uniqueId == shownLicense }
        ?.let { LicenseSheet(it, onDismiss = { shownLicense = null }) }
}

/** As wide as the screen, up to a readable width. */
private fun Modifier.page() = widthIn(max = PAGE_MAX_WIDTH).fillMaxWidth()

/** The shape of the row at [index] in a group of [count] rows: round only at the group's ends. */
private fun groupShape(index: Int, count: Int): Shape {
    val top = if (index == 0) GROUP_RADIUS else ROW_RADIUS
    val bottom = if (index == count - 1) GROUP_RADIUS else ROW_RADIUS
    return RoundedCornerShape(top, top, bottom, bottom)
}

/** The app's mark glowing above its name, what it does and its version. */
@Composable
private fun Hero() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.page(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(HERO_GLOW_SIZE)
                .background(
                    Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = HERO_GLOW_ALPHA), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(HERO_DISC_SIZE)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_mark), null, Modifier.size(HERO_MARK_SIZE))
            }
        }
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(ROW_CONTENT_GAP))
        Text(
            stringResource(R.string.version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier =
                Modifier.clip(CircleShape)
                    .background(colors.surfaceContainer)
                    .padding(
                        horizontal = VERSION_PADDING_HORIZONTAL,
                        vertical = VERSION_PADDING_VERTICAL,
                    ),
        )
        Spacer(Modifier.height(SECTION_TITLE_PADDING_TOP))
    }
}

/** That photos stay on the phone, in the accent color, as the app's promise. */
@Composable
private fun PrivateCard() {
    val content = MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        Modifier.page()
            .clip(RoundedCornerShape(GROUP_RADIUS))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(ROW_PADDING_HORIZONTAL),
        horizontalArrangement = Arrangement.spacedBy(ROW_CONTENT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(BADGE_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_private),
                null,
                Modifier.size(BADGE_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column {
            Text(
                stringResource(R.string.private_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            Text(
                stringResource(R.string.private_text),
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = ROW_TEXT_ALPHA),
            )
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.page()
                .padding(
                    start = ROW_PADDING_HORIZONTAL,
                    top = SECTION_TITLE_PADDING_TOP,
                    bottom = SECTION_TITLE_PADDING_BOTTOM,
                ),
    )
}

/**
 * A row of a group, of [shape], with a [title] and an optional [text] below it, between an optional
 * [leading] badge and [trailing] icon; tappable when [onClick] is given, smaller when [dense].
 */
@Composable
private fun ListRow(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    text: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: Painter? = null,
    trailingRotation: Float = 0f,
    onClick: (() -> Unit)? = null,
    dense: Boolean = false,
) {
    Row(
        modifier
            .page()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            .heightIn(min = if (dense) LICENSE_ROW_MIN_HEIGHT else ROW_MIN_HEIGHT)
            .padding(
                horizontal = ROW_PADDING_HORIZONTAL,
                vertical = if (dense) LICENSE_ROW_PADDING_VERTICAL else ROW_PADDING_VERTICAL,
            ),
        horizontalArrangement = Arrangement.spacedBy(ROW_CONTENT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style =
                    if (dense) MaterialTheme.typography.bodyLarge
                    else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (text != null) {
                Text(
                    text,
                    style =
                        if (dense) MaterialTheme.typography.bodySmall
                        else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Icon(
                trailing,
                null,
                Modifier.size(TRAILING_ICON_SIZE).rotate(trailingRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row's [icon] on a tinted disc. */
@Composable
private fun IconBadge(icon: Painter) {
    Box(
        Modifier.size(BADGE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            null,
            Modifier.size(BADGE_ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** A tip's [number], drawn like a counted point on the photo. */
@Composable
private fun TipPoint(number: Int) {
    val locale = LocalConfiguration.current.locales[0]
    Box(
        Modifier.size(TIP_POINT_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = POINT_ALPHA))
            .border(TIP_POINT_OUTLINE, POINT_OUTLINE_COLOR, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            NumberFormat.getIntegerInstance(locale).format(number),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/**
 * Opens [uri] in the app that handles it, like the browser or the mail app, which may go online
 * where this app cannot.
 */
private fun Context.openLink(uri: String) {
    val parsed = uri.toUri()
    val action = if (parsed.scheme == "mailto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW
    try {
        startActivity(Intent(action, parsed))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app opens $uri", e)
        Toast.makeText(this, R.string.error_no_app, Toast.LENGTH_SHORT).show()
    }
}
