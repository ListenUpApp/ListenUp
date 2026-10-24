package com.calypsan.listenup.client.features.nowplaying

import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.calypsan.listenup.client.features.nowplaying.components.BookSentence
import com.calypsan.listenup.client.features.nowplaying.components.PlayerCover
import com.calypsan.listenup.client.features.nowplaying.components.PlayerStateCard
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTitleBlock
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSettingsRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.design.theme.Spacing

// Horizontal padding for the full-screen overlay.
private val PANEL_HORIZONTAL_PADDING = 32.dp

// Vertical padding inside the content area, below the header.
private val PANEL_VERTICAL_PADDING = 24.dp

// The same, on a window too short to spare it (a phone on its side).
private val SHORT_PANEL_VERTICAL_PADDING = 8.dp

// When short, the secondary-actions row (48 dp) and its 8 dp gap sit under the cover.
private val SHORT_SECONDARY_ROW_ALLOWANCE = 56.dp

// Gap between the cover (left) and the controls (right).
private val PANE_GAP = 40.dp

// Cover cap so the artwork stays tasteful on very large viewports.
private val MAX_COVER_SIZE = 520.dp

// Controls column cap so the scrubber/transport stay readable rather than stretching edge-to-edge.
private val CONTROLS_MAX_WIDTH = 520.dp

/**
 * Wide (landscape phone, foldable inner display) Now Playing: the phone's primitives re-skinned side by side, not yet
 * redesigned (spec §5.2; PR 5 redesigns the wide layout).
 * - **Header** ([PlayerTopBar] with `wide = true`): tonal collapse, "Now playing" over the series or title, the status
 *   chip and More.
 * - **Left pane**: the flat square cover ([PlayerCover]), sized off the available height.
 * - **Right pane**: the title block, the scrubber with the book sentence, the transport (or the state card in its
 *   place) and the settings row.
 *
 * On a short window — a phone on its side — the header drops to the phone bar, the settings move under the cover,
 * and the controls tighten (see [WideControls]), so the transport still fits without scrolling. Should a window be
 * shorter still, the controls column scrolls rather than clipping the transport.
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
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onMenuItem: (PlayerMenuItem) -> Unit,
    onCardAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isShort =
        !currentWindowAdaptiveInfoV2().windowSizeClass.isHeightAtLeastBreakpoint(
            WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
        )
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Clear the status bar, and on a phone on its side the camera cutout and the side nav bar.
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Desktop-style header: tonal collapse · "Now playing"/title · cast · overflow.
            PlayerTopBar(
                state = state,
                onCollapse = onCollapse,
                onMenuItem = onMenuItem,
                wide = !isShort,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PANEL_HORIZONTAL_PADDING),
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
                // Square cover capped by the left half's width AND the available height, so the whole
                // player fits without scrolling and the artwork never clamps to a portrait sliver. When
                // short, the secondary actions sit under the cover, so the cover leaves them room.
                val halfWidth = (maxWidth - PANE_GAP) / 2
                val coverHeight = if (isShort) maxHeight - SHORT_SECONDARY_ROW_ALLOWANCE else maxHeight
                val coverSize = min(min(coverHeight, halfWidth), MAX_COVER_SIZE)
                val secondaryActions: @Composable () -> Unit = {
                    // Speed · Sleep · Chapters · Cast; Chapters stays until PR 5's always-visible list.
                    PlayerSettingsRow(
                        speed = state.settings.speed,
                        sleepTimer = state.sleepTimer,
                        castingTo = (state.source as? AudioSource.Casting)?.deviceName,
                        sleepCountdownMs = {
                            SleepCountdowns.remainingMs(
                                state.sleepTimer,
                                progress(),
                                state.settings.speed,
                            )
                        },
                        showsChapters = true,
                        onSpeedClick = onSpeedClick,
                        onSleepClick = onSleepClick,
                        onChaptersClick = onChaptersClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PANE_GAP),
                ) {
                    // LEFT: cover, vertically centred in its half.
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlayerCover(state = state, side = coverSize)
                        if (isShort) {
                            Spacer(Modifier.height(8.dp))
                            secondaryActions()
                        }
                    }

                    // RIGHT: metadata + controls, vertically centred, left-aligned. It scrolls only
                    // when it can't fit, so a drag on it still pulls the player down otherwise.
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
                                onCardAction = onCardAction,
                                onRate = onRate,
                            )
                            if (!isShort) {
                                Spacer(Modifier.height(Spacing.xl))
                                secondaryActions()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The right pane: the title block, the scrubber with the book sentence, and the transport or the state card in its
 * place (C5). When [isShort] (a phone on its side) the title drops to one line, the counter and the bylines go (the cover and the
 * More menu still lead to the book), and the play button shrinks to 64 dp.
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
    )

    Spacer(Modifier.height(if (isShort) 12.dp else 32.dp))

    PlayerScrubber(
        progress = progress,
        isBusy = state.isBusy || card is PlayerCard.Problem,
        onSeek = onSeek,
        modifier = Modifier.fillMaxWidth(),
        center = { BookSentence(progress) },
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
