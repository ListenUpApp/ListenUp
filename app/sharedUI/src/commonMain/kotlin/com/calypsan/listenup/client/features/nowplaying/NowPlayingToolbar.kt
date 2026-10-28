package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.runtime.LaunchedEffect
import com.calypsan.listenup.client.design.components.PlaybackSlider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.features.nowplaying.components.Ctrl
import com.calypsan.listenup.client.features.nowplaying.components.MiniBookSwitch
import com.calypsan.listenup.client.features.nowplaying.components.MiniLineFit
import com.calypsan.listenup.client.features.nowplaying.components.MiniPrimaryButton
import com.calypsan.listenup.client.features.nowplaying.components.SkipGlyphs
import com.calypsan.listenup.client.features.nowplaying.components.SleepSettingButton
import com.calypsan.listenup.client.features.shell.components.NavigationRailWidth
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import listenup.composeapp.generated.resources.player_mini_open
import listenup.composeapp.generated.resources.player_playback_speed
import listenup.composeapp.generated.resources.player_skip_backward
import listenup.composeapp.generated.resources.player_skip_forward
import org.jetbrains.compose.resources.stringResource

/** The narrowest toolbar that holds the whole wide set (§3.5); below it the scrubber and the settings step aside. */
internal val ToolbarFullSetMinWidth = 840.dp

/** The gap the toolbar floats at, from the window edges and the rail (FloatingToolbar's external padding). */
internal val ToolbarMargin = 16.dp

/** M3 elevation level 3: the floating toolbar is the one shadow in the player (S5). */
internal val ToolbarElevation = 6.dp

/**
 * Where the toolbar floats: beside the rail when the shell shows one, else across the window, [ToolbarMargin] clear of
 * every edge. Shared by `NowPlayingHost` and the rail test, so the contract "never under the rail" is one value.
 */
internal fun nowPlayingToolbarPadding(besideRail: Boolean): PaddingValues =
    PaddingValues(
        start = if (besideRail) NavigationRailWidth + ToolbarMargin else ToolbarMargin,
        end = ToolbarMargin,
        bottom = ToolbarMargin,
    )

/** The body's width beside the scrubber (`Mini-AndroidTablet`); below the full set it takes the spare width. */
private val ToolbarBodyWidth = 248.dp

/** The toolbar's cover. */
private val ToolbarCoverSize = 60.dp

/** M18: the open target's focus treatment on TV and with a keyboard, kept from the old docked bar. */
private const val FOCUSED_SCALE = 1.02f

/**
 * The wide mini player: tablets, unfolded foldables and TV (spec §5.1, board `Mini-AndroidTablet`). An M3 floating
 * toolbar on `surfaceContainer` with 28dp corners and the player's one shadow. Back N follows the shared state;
 * forward stays. The chapter scrubber and Speed · Sleep · Chapters appear when the toolbar is at least
 * [ToolbarFullSetMinWidth] wide — the width decides, not the device (sharedUI rule 11).
 *
 * @param onPrimary The main button's action, decided by [PlayerUiState.Loaded.primary].
 * @param onKeepListening The main button's action during the sleep fade, where it reads Keep listening.
 */
@Suppress("LongParameterList") // one input per control the board draws
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingToolbar(
    state: PlayerUiState,
    progress: () -> PlayerProgress,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onKeepListening: () -> Unit,
    skipBackwardSec: Int,
    skipForwardSec: Int,
    modifier: Modifier = Modifier,
) {
    // Whether the toolbar is coming back from under Now Playing rather than arriving with a book.
    var coveredByPlayer by remember { mutableStateOf(isExpanded) }
    LaunchedEffect(isExpanded) { if (isExpanded) coveredByPlayer = true }
    LaunchedEffect(state is PlayerUiState.Loaded) { if (state !is PlayerUiState.Loaded) coveredByPlayer = false }
    AnimatedVisibility(
        visible = state is PlayerUiState.Loaded && !isExpanded,
        // Now Playing fades through over the toolbar, so the toolbar fades under it; a book arriving or leaving slides.
        enter = if (coveredByPlayer) fadeIn() else slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = if (isExpanded) fadeOut() else slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        val loaded = state as? PlayerUiState.Loaded ?: return@AnimatedVisibility
        val line = MiniPlayerLines.of(loaded)
        val tinted = line is MiniPlayerLine.Problem && line.isTinted
        val actions =
            ToolbarActions(
                onOpen = onTap,
                onPrimary = onPrimary,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onSeek = onSeek,
                onSpeedClick = onSpeedClick,
                onSleepClick = onSleepClick,
                onChaptersClick = onChaptersClick,
                onKeepListening = onKeepListening,
            )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fullSet = maxWidth >= ToolbarFullSetMinWidth
            HorizontalFloatingToolbar(
                expanded = true,
                colors =
                    FloatingToolbarDefaults.standardFloatingToolbarColors(
                        toolbarContainerColor = if (tinted) MaterialTheme.colorScheme.errorContainer else Color.Unspecified,
                        toolbarContentColor = if (tinted) MaterialTheme.colorScheme.onErrorContainer else Color.Unspecified,
                    ),
                shape = MaterialTheme.shapes.extraLarge,
                expandedShadowElevation = ToolbarElevation,
                modifier = Modifier.fillMaxWidth().swipeUpToExpand(onTap),
            ) {
                // The container stays; only its content moves when the book changes.
                MiniBookSwitch(loaded, Modifier.weight(1f)) { shown ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ToolbarContent(
                            state = shown,
                            progress = progress,
                            fullSet = fullSet,
                            actions = actions,
                            skipBackwardSec = skipBackwardSec,
                            skipForwardSec = skipForwardSec,
                        )
                    }
                }
            }
        }
    }
}

/** What each of the toolbar's controls does. */
private data class ToolbarActions(
    val onOpen: () -> Unit,
    val onPrimary: () -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onSpeedClick: () -> Unit,
    val onSleepClick: () -> Unit,
    val onChaptersClick: () -> Unit,
    val onKeepListening: () -> Unit,
)

@Composable
private fun RowScope.ToolbarContent(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    fullSet: Boolean,
    actions: ToolbarActions,
    skipBackwardSec: Int,
    skipForwardSec: Int,
) {
    val line = MiniPlayerLines.of(state)
    ToolbarBody(
        state = state,
        line = line,
        progress = progress,
        onOpen = actions.onOpen,
        modifier = if (fullSet) Modifier.width(ToolbarBodyWidth) else Modifier.weight(1f),
    )
    if (state.showsSkipBack) {
        Ctrl(
            icon = SkipGlyphs.backward(skipBackwardSec),
            contentDescription = stringResource(Res.string.player_skip_backward, skipBackwardSec),
            onClick = actions.onSkipBack,
            size = MinTouchTarget,
            iconSize = MiniPlayerGlyphSize,
            tint = LocalContentColor.current,
        )
    }
    MiniPrimaryButton(
        primary = state.primary,
        bookTitle = state.book.title,
        onClick = actions.onPrimary,
        size = IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide),
        playLabel = miniPlayLabel(line),
        busyLabel = miniBusyLabel(line),
        onKeepListening = actions.onKeepListening.takeIf { line == MiniPlayerLine.FadingOut },
    )
    Ctrl(
        icon = SkipGlyphs.forward(skipForwardSec),
        contentDescription = stringResource(Res.string.player_skip_forward, skipForwardSec),
        onClick = actions.onSkipForward,
        size = MinTouchTarget,
        iconSize = MiniPlayerGlyphSize,
        tint = LocalContentColor.current,
    )
    if (fullSet) {
        ToolbarScrubber(
            progress = progress,
            isPlaying = state.isAudible,
            onSeek = actions.onSeek,
            modifier = Modifier.weight(1f),
        )
        SpeedButton(speed = state.settings.speed, onClick = actions.onSpeedClick)
        SleepSettingButton(
            timer = state.sleepTimer,
            countdownMs = { SleepCountdowns.remainingMs(state.sleepTimer, progress(), state.settings.speed) },
            onClick = actions.onSleepClick,
            tonalWhenIdle = false,
        )
        Ctrl(
            icon = Icons.AutoMirrored.Filled.List,
            contentDescription = stringResource(Res.string.player_chapters),
            onClick = actions.onChaptersClick,
            size = MinTouchTarget,
            iconSize = MiniPlayerGlyphSize,
            tint = LocalContentColor.current,
        )
    }
    Ctrl(
        icon = Icons.Default.KeyboardArrowUp,
        contentDescription = stringResource(Res.string.player_mini_open),
        onClick = actions.onOpen,
        size = MinTouchTarget,
        iconSize = MiniPlayerGlyphSize,
        tint = LocalContentColor.current,
        pressFeedback = false,
    )
}

/**
 * The open target, with M18's focus treatment for TV and keyboards: a 1.02 scale and a `primary` outline.
 *
 * It declares itself a minimum interactive component, which it is (a 72dp target). The toolbar balances its padding
 * from the leftmost control's touch-target alignment lines; without the body's own lines that is back N, 250dp in,
 * and the toolbar grew 500dp tall to "balance" it.
 */
@Composable
private fun ToolbarBody(
    state: PlayerUiState.Loaded,
    line: MiniPlayerLine,
    progress: () -> PlayerProgress,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(if (focused) FOCUSED_SCALE else 1f, label = "toolbar_focus_scale")
    val outline = MaterialTheme.colorScheme.primary
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .onFocusChanged { focused = it.hasFocus }
                .scale(focusScale)
                .then(if (focused) Modifier.border(2.dp, outline, shape) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniBody(
            state = state,
            line = line,
            progress = progress,
            onOpen = onOpen,
            fit = MiniLineFit.WithoutTime,
            coverSize = ToolbarCoverSize,
        )
    }
}

/** The one playback slider (chapter-scoped), elapsed and −remaining beside it. Holds its drag; seeks on release. */
@Composable
private fun ToolbarScrubber(
    progress: () -> PlayerProgress,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = progress()
    PlaybackSlider(
        fraction = current.chapterFraction,
        elapsedMs = current.chapterPositionMs,
        durationMs = current.chapterDurationMs,
        isPlaying = isPlaying,
        onSeek = onSeek,
        modifier = modifier,
        timesBeside = true,
    )
}

/** Speed: a tonal button carrying the rate (§5.1 M13). */
@Composable
private fun SpeedButton(
    speed: Float,
    onClick: () -> Unit,
) {
    val rate = formatPlaybackSpeed(speed)
    val name = stringResource(Res.string.player_playback_speed)
    FilledTonalButton(
        onClick = onClick,
        modifier =
            Modifier.heightIn(min = MinTouchTarget).semantics {
                contentDescription = name
                stateDescription = rate
            },
    ) { Text(rate) }
}
