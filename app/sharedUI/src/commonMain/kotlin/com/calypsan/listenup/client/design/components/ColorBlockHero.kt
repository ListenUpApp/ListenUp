package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.HeroInk
import com.calypsan.listenup.client.design.util.isLargeFontScale
import kotlin.math.roundToInt

private val HERO_BADGE_SIZE = 48.dp

/**
 * The canonical color-blocked screen header: a [MaterialTheme.colorScheme.primaryContainer] [Surface]
 * with large rounded bottom corners holding a back [IconButton], an optional UPPERCASE [overline]
 * (e.g. the server name), a large emphasized [title] in `onPrimaryContainer`, and a trailing
 * [ScallopBadge] glyph. An optional [supportingText] paragraph renders below the title.
 *
 * Shared across the admin surfaces — the Admin landing screen and the Create-Invite screen both
 * compose this so the color-blocked hero stays a single source of truth.
 *
 * @param title Large emphasized heading rendered in `onPrimaryContainer`.
 * @param badgeIcon Glyph rendered inside the trailing scallop badge.
 * @param onBack Invoked when the back button is tapped.
 * @param modifier Modifier for the hero surface.
 * @param overline Optional UPPERCASE eyebrow above the title (e.g. server name); hidden when null
 *   or blank.
 * @param supportingText Optional paragraph rendered below the title.
 * @param content Optional trailing slot rendered full-width below the title/supporting text — used
 *   to host a [WizardStepTracker] inside the wizard chrome.
 * @param actions Optional top-bar actions (e.g. a [SaveAction]). When present, the back button and
 *   the actions share a top row, the way a Material large top app bar carries them, and the title
 *   drops to its own row beneath — so an action never squeezes the display-size title.
 * @param scrollBehavior From [rememberHeroScrollBehavior]: at a large font the hero slides away as the
 *   content scrolls up and returns on the way back, instead of holding a third of the screen. Pass the
 *   same behaviour's `nestedScrollConnection` to the screen's scaffold. Null keeps the hero pinned.
 *
 * At a large font the title steps down a size and hyphenates, so a long word ("Administration") breaks at
 * a syllable rather than leaving a lone letter on its own line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorBlockHero(
    title: String,
    badgeIcon: ImageVector,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overline: String? = null,
    supportingText: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
    Surface(
        modifier = modifier.then(scrollBehavior?.let { Modifier.slidesAway(it, keepPx = statusBarTop) } ?: Modifier),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = ContentShapes.hero,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The primaryContainer Surface bleeds edge-to-edge behind the status bar; inset
                    // only the content so the back button clears the system clock and stays tappable.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            val backButton: @Composable () -> Unit = {
                IconButton(
                    onClick = {
                        haptics.press()
                        onBack()
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    backButton()
                    Spacer(Modifier.weight(1f))
                    actions()
                }
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Under an actions row the title aligns with the back arrow's glyph.
                        .then(if (actions != null) Modifier.padding(start = 12.dp, top = 8.dp) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (actions == null) backButton()
                HeroHeadline(title = title, overline = overline, modifier = Modifier.weight(1f))
                ScallopBadge(size = HERO_BADGE_SIZE, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeroInk.muted(),
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, end = 8.dp),
                )
            }
            if (content != null) {
                Column(modifier = Modifier.padding(start = 8.dp, top = 18.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * The hero's optional UPPERCASE [overline] over its [title], the screen's heading. At a large font the title
 * steps down a size and hyphenates.
 */
@Composable
private fun HeroHeadline(
    title: String,
    overline: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (!overline.isNullOrBlank()) {
            Text(
                text = overline.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = HeroInk.muted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = title,
            style =
                if (isLargeFontScale()) {
                    MaterialTheme.typography.headlineSmall.copy(hyphens = Hyphens.Auto)
                } else {
                    MaterialTheme.typography.headlineMedium
                },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * The scroll behaviour a [ColorBlockHero] takes at a large font: it slides away as the content scrolls up and
 * comes back as soon as it scrolls down (Material's enter-always top app bar). Null below [isLargeFontScale]'s
 * threshold, where the hero stays pinned as designed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberHeroScrollBehavior(): TopAppBarScrollBehavior? =
    if (isLargeFontScale()) TopAppBarDefaults.enterAlwaysScrollBehavior() else null

/**
 * Lays the hero out at its full height minus what [behavior] has scrolled away, never less than [keepPx] — the
 * status bar's band, which stays in the hero's colour behind the clock while the rest folds up under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.slidesAway(
    behavior: TopAppBarScrollBehavior,
    keepPx: Int,
): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val floor = keepPx.coerceAtMost(placeable.height)
        val limit = (floor - placeable.height).toFloat()
        if (behavior.state.heightOffsetLimit != limit) behavior.state.heightOffsetLimit = limit
        val shown = (placeable.height + behavior.state.heightOffset.roundToInt()).coerceIn(floor, placeable.height)
        layout(placeable.width, shown) { placeable.place(0, 0) }
    }
