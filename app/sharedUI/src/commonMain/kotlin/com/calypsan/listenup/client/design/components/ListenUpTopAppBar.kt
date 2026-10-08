package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.util.isLargeFontScale
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/** The most lines a bar title takes at a large font before it ellipsizes. */
private const val LARGE_TEXT_TITLE_LINES = 2

/** The breathing room above and below a large-font title in its grown bar. */
private val LargeTextBarPadding = 16.dp

/**
 * The canonical standard top app bar — every feature screen's bar is this one. A thin wrapper over
 * Material 3 [TopAppBar] that presets the app's ExtraBold title (a TalkBack heading, one line,
 * ellipsized), an optional [subtitle], and an optional leading navigation button with a press
 * haptic.
 *
 * It self-insets the status bar (Material 3 [TopAppBar]'s default `windowInsets`) while its
 * container still draws to the screen top — the idiomatic edge-to-edge behavior. Feature
 * screens use this instead of hand-rolling a `Row`, so the "controls trapped under the status
 * bar" bug class (e.g. the old `EditProfileTopBar`) cannot recur. Immersive screens that bleed
 * a hero behind the status bar use [HeroNavRow] instead.
 *
 * @param title Bar title text; announced as a heading.
 * @param modifier Modifier for the bar.
 * @param subtitle Optional second line under the title (e.g. which book an editor is editing).
 * @param onBack If non-null, renders a leading navigation [IconButton] that invokes it.
 * @param navigationIcon The leading glyph — Back by default; a Close where the bar dismisses a
 *   pane or an overlay rather than stepping back.
 * @param navigationContentDescription The navigation button's accessible name; "Back" when null.
 * @param scrollBehavior Optional Material scroll behavior, e.g. to lift the bar's container colour
 *   while content scrolls under it.
 * @param colors The bar's container and content colours.
 * @param actions Trailing action slot.
 *
 * At a large font one line holds only a few letters of a title ("The Two To…"), so the title may take a
 * second line and the bar grows to hold what it shows, as Material's flexible bars do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenUpTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    navigationIcon: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack,
    navigationContentDescription: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val haptics = LocalHaptics.current
    val largeText = isLargeFontScale()
    val titleStyle = MaterialTheme.typography.headlineSmall
    val subtitleStyle = MaterialTheme.typography.labelMedium
    var titleLines by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val expandedHeight =
        if (largeText) {
            with(density) {
                val subtitleHeight = if (subtitle != null) subtitleStyle.lineHeight.toDp() else 0.dp
                maxOf(
                    TopAppBarDefaults.TopAppBarExpandedHeight,
                    titleStyle.lineHeight.toDp() * titleLines + subtitleHeight + LargeTextBarPadding,
                )
            }
        } else {
            TopAppBarDefaults.TopAppBarExpandedHeight
        }
    TopAppBar(
        modifier = modifier,
        expandedHeight = expandedHeight,
        title = {
            Column {
                Text(
                    text = title,
                    style = titleStyle,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = if (largeText) LARGE_TEXT_TITLE_LINES else 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleLines = it.lineCount },
                    modifier = Modifier.semantics { heading() },
                )
                subtitle?.let { subtitleText ->
                    Text(
                        text = subtitleText,
                        style = subtitleStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(
                    onClick = {
                        haptics.press()
                        onBack()
                    },
                ) {
                    Icon(
                        imageVector = navigationIcon,
                        contentDescription = navigationContentDescription ?: stringResource(Res.string.common_back),
                    )
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = colors,
    )
}
