package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.NowPlayingChapterPanel
import com.calypsan.listenup.client.features.nowplaying.components.PlayerCover
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSettingsRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerStateCard
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTitleBlock
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.features.nowplaying.components.TitleArrangement
import com.calypsan.listenup.client.foldable.LocalFold
import com.calypsan.listenup.client.foldable.Posture
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.SleepCountdowns

/** The wide cover's test tag. */
internal const val WIDE_COVER_TAG = "player-wide-cover"

// Horizontal padding for the full-screen overlay.
private val PANEL_HORIZONTAL_PADDING = 32.dp

// Vertical padding inside the content area, below the header.
private val PANEL_VERTICAL_PADDING = 24.dp

// The same, on a window too short to spare it (a phone on its side).
private val SHORT_PANEL_VERTICAL_PADDING = 8.dp

// When short, the secondary-actions row (48 dp) and its 8 dp gap sit under the cover.
private val SHORT_SECONDARY_ROW_ALLOWANCE = 56.dp

// Cover cap for cover-beside-controls, so the artwork stays tasteful on very large viewports.
private val MAX_COVER_SIZE = 520.dp

// Controls column cap so the scrubber/transport stay readable rather than stretching edge-to-edge.
private val CONTROLS_MAX_WIDTH = 520.dp

// The board drops the three-column cover 40dp below the column's top, level with the title.
private val COVER_DROP = 40.dp

/**
 * Wide Now Playing (spec §5.3, `NP-AndroidTablet`): Expanded windows, a phone on its side and book posture. It divides
 * the space it actually gets by [widePanes]:
 * - **Three columns** ([WidePanes.ThreeColumns]): the cover · the player column · the chapter panel.
 * - **Column and panel** ([WidePanes.ColumnAndPanel]): the cover over the player column, beside the panel (an upright
 *   tablet).
 * - **Cover and controls** ([WidePanes.CoverAndControls]): the cover beside the controls, with Chapters in the
 *   settings row opening the sheet (a short tablet, book posture, a phone on its side).
 *
 * The header ([PlayerTopBar] with `wide = true`) reads "Now playing" with the status and More (P3); the series is a link
 * above the title (P10), so More leaves Go to series out. Where the panel shows ([NowPlayingChapterPanel], P33),
 * Chapters leaves the settings row (P22). A chapterless book never gets the panel, and has no Chapters to pick.
 *
 * On a short window — a phone on its side — the header drops to the phone bar, the settings move under the cover,
 * and the controls tighten (see [WideControls]), so the transport still fits without scrolling. Should a window be
 * shorter still, or the text larger, the player column scrolls rather than clipping.
 *
 * @param onSeekToChapter Play from the chapter at this zero-based index (a panel row).
 * @param onChapterPanelShown Whether the chapter panel is on screen, each time that changes, and false when this
 *   layout leaves; the host closes a chapter sheet left open beneath it.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun WideNowPlaying(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    onCollapse: () -> Unit,
    onPrimary: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onSeekToChapter: (Int) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onMenuItem: (PlayerMenuItem) -> Unit,
    onCardAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onChapterPanelShown: (Boolean) -> Unit = {},
) {
    val isShort =
        !currentWindowAdaptiveInfoV2().windowSizeClass.isHeightAtLeastBreakpoint(
            WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
        )
    val isBookPosture = LocalFold.current.posture == Posture.BOOK
    val reportPanel by rememberUpdatedState(onChapterPanelShown)
    DisposableEffect(Unit) { onDispose { reportPanel(false) } }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Clear the status bar, and on a phone on its side the camera cutout and the side nav bar.
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            PlayerTopBar(
                state = state,
                onCollapse = onCollapse,
                onMenuItem = onMenuItem,
                wide = !isShort,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PANEL_HORIZONTAL_PADDING - Spacing.sm),
            )

            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(
                            horizontal = PANEL_HORIZONTAL_PADDING,
                            vertical = if (isShort) SHORT_PANEL_VERTICAL_PADDING else PANEL_VERTICAL_PADDING,
                        ),
            ) {
                val width = maxWidth
                val height = maxHeight
                val panes =
                    widePanes(maxWidth = width, maxHeight = height, isShort = isShort, isBookPosture = isBookPosture)
                val showsPanel = panes != WidePanes.CoverAndControls && state.chapters.isNotEmpty()
                LaunchedEffect(showsPanel) { reportPanel(showsPanel) }

                val settingsRow: @Composable () -> Unit = {
                    PlayerSettingsRow(
                        speed = state.settings.speed,
                        sleepTimer = state.sleepTimer,
                        sleepCountdownMs = {
                            SleepCountdowns.remainingMs(state.sleepTimer, progress(), state.settings.speed)
                        },
                        // Wherever the panel can show, the list is always there (P22); a chapterless book has none.
                        showsChapters = panes == WidePanes.CoverAndControls && state.chapters.isNotEmpty(),
                        onSpeedClick = onSpeedClick,
                        onSleepClick = onSleepClick,
                        onChaptersClick = onChaptersClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val controls: @Composable () -> Unit = {
                    WideControls(
                        state = state,
                        progress = progress,
                        isShort = isShort,
                        onPrimary = onPrimary,
                        onSeek = onSeek,
                        onSkipBack = onSkipBack,
                        onSkipForward = onSkipForward,
                        onPreviousChapter = onPreviousChapter,
                        onNextChapter = onNextChapter,
                        onGoToContributor = onGoToContributor,
                        onShowAuthorPicker = onShowAuthorPicker,
                        onShowNarratorPicker = onShowNarratorPicker,
                        onSeries = { onMenuItem(PlayerMenuItem.GoToSeries(it)) },
                        onCardAction = onCardAction,
                        onRate = onRate,
                    )
                }

                if (panes == WidePanes.CoverAndControls) {
                    CoverBesideControls(
                        state = state,
                        isShort = isShort,
                        maxWidth = width,
                        maxHeight = height,
                        controls = controls,
                        settingsRow = settingsRow,
                    )
                } else {
                    PanesWithPanel(
                        state = state,
                        progress = progress,
                        panes = panes,
                        showsPanel = showsPanel,
                        maxWidth = width,
                        maxHeight = height,
                        controls = controls,
                        settingsRow = settingsRow,
                        onSeekToChapter = onSeekToChapter,
                    )
                }
            }
        }
    }
}

/**
 * [WidePanes.ThreeColumns] (cover · player column · panel) and [WidePanes.ColumnAndPanel] (the cover over the player
 * column, beside the panel). Without chapters ([showsPanel] false) the player takes the panel's width.
 */
@Suppress("LongParameterList")
@Composable
private fun PanesWithPanel(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    panes: WidePanes,
    showsPanel: Boolean,
    maxWidth: Dp,
    maxHeight: Dp,
    controls: @Composable () -> Unit,
    settingsRow: @Composable () -> Unit,
    onSeekToChapter: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(PaneGap),
    ) {
        val panelSpace = if (showsPanel) PanelWidth + PaneGap else 0.dp
        if (panes == WidePanes.ThreeColumns) {
            val coverSide =
                min(min(MaxWideCover, maxWidth - panelSpace - PaneGap - MinControlsWidth), maxHeight - COVER_DROP)
            PlayerCover(
                state = state,
                side = coverSide,
                modifier = Modifier.padding(top = COVER_DROP).testTag(WIDE_COVER_TAG),
            )
        }
        val stackedCoverSide = min(min(MaxWideCover, maxWidth - panelSpace), maxHeight - MinControlsHeight)
        PlayerColumn(
            state = state,
            coverSide = stackedCoverSide.takeIf { panes == WidePanes.ColumnAndPanel },
            controls = controls,
            settingsRow = settingsRow,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (showsPanel) {
            NowPlayingChapterPanel(
                rows = state.chapters,
                currentIndex = state.chapter.index,
                bookDurationMs = state.book.durationMs,
                progress = progress,
                onSeekToChapter = onSeekToChapter,
                modifier = Modifier.width(PanelWidth).fillMaxHeight(),
            )
        }
    }
}

/**
 * The board's player column: the cover over it when [coverSide] is set (an upright tablet), then the book, the
 * scrubber, the transport and the settings row. It scrolls only when it can't fit (large text), so a drag on it still
 * pulls the player down otherwise.
 */
@Composable
private fun PlayerColumn(
    state: PlayerUiState.Loaded,
    coverSide: Dp?,
    controls: @Composable () -> Unit,
    settingsRow: @Composable () -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
                .widthIn(max = CONTROLS_MAX_WIDTH)
                .verticalScroll(scroll, enabled = scroll.canScrollForward || scroll.canScrollBackward)
                .padding(top = Spacing.xl),
    ) {
        if (coverSide != null) {
            PlayerCover(state = state, side = coverSide, modifier = Modifier.testTag(WIDE_COVER_TAG))
            Spacer(Modifier.height(Spacing.xl))
        }
        controls()
        Spacer(Modifier.height(Spacing.xl))
        settingsRow()
    }
}

/**
 * Cover beside the controls, the two halves equal so book posture's hinge falls in the gap. Short (a phone on its
 * side), the settings sit under the cover so the transport fits; otherwise under the controls.
 */
@Suppress("LongParameterList")
@Composable
private fun CoverBesideControls(
    state: PlayerUiState.Loaded,
    isShort: Boolean,
    maxWidth: Dp,
    maxHeight: Dp,
    controls: @Composable () -> Unit,
    settingsRow: @Composable () -> Unit,
) {
    // Square cover capped by the left half's width AND the available height, so the whole player fits without
    // scrolling and the artwork never clamps to a portrait sliver. When short, the secondary actions sit under the
    // cover, so the cover leaves them room.
    val halfWidth = (maxWidth - PaneGap) / 2
    val coverHeight = if (isShort) maxHeight - SHORT_SECONDARY_ROW_ALLOWANCE else maxHeight
    val coverSize = min(min(coverHeight, halfWidth), MAX_COVER_SIZE)
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaneGap),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerCover(state = state, side = coverSize, modifier = Modifier.testTag(WIDE_COVER_TAG))
            if (isShort) {
                Spacer(Modifier.height(8.dp))
                settingsRow()
            }
        }

        val controlsScroll = rememberScrollState()
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .widthIn(max = CONTROLS_MAX_WIDTH),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(
                            controlsScroll,
                            enabled = controlsScroll.canScrollForward || controlsScroll.canScrollBackward,
                        ),
                horizontalAlignment = Alignment.Start,
            ) {
                controls()
                if (!isShort) {
                    Spacer(Modifier.height(Spacing.xl))
                    settingsRow()
                }
            }
        }
    }
}

/**
 * The controls: the title block, the scrubber, and the transport or the state card in its place
 * (C5). Under the wide header the book takes the board's order, the series a link above the title
 * ([TitleArrangement.Wide]). When [isShort] (a phone on its side, under the phone bar) the title drops to one line, the
 * counter and the bylines go (the cover and the More menu still lead to the book), and the play button shrinks to 64 dp.
 */
@Suppress("LongParameterList")
@Composable
private fun WideControls(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    isShort: Boolean,
    onPrimary: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onSeries: (String) -> Unit,
    onCardAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
) {
    val card = PlayerCards.of(state)
    PlayerTitleBlock(
        state = state,
        onContributor = onGoToContributor,
        onShowAuthors = onShowAuthorPicker,
        onShowNarrators = onShowNarratorPicker,
        alignment = Alignment.Start,
        titleLines = if (isShort) 1 else 2,
        showsBylines = !isShort,
        showsCounter = !isShort,
        arrangement = if (isShort) TitleArrangement.Phone else TitleArrangement.Wide,
        onSeries = onSeries,
    )

    Spacer(Modifier.height(if (isShort) 12.dp else Spacing.md))

    PlayerScrubber(
        progress = progress,
        isBusy = state.isBusy || card is PlayerCard.Problem,
        onSeek = onSeek,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(if (isShort) 8.dp else 24.dp))

    if (card != null) {
        PlayerStateCard(card = card, onAction = onCardAction, onRate = onRate, modifier = Modifier.fillMaxWidth())
    } else {
        PlayerTransport(
            state = state,
            onPrimary = onPrimary,
            onSkipBack = onSkipBack,
            onSkipForward = onSkipForward,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            playSize = if (isShort) 64.dp else 96.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
