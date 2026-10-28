package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.calypsan.listenup.client.features.nowplaying.components.PlayerCover
import com.calypsan.listenup.client.features.nowplaying.components.PlayerStateCard
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTitleBlock
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSettingsRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState

/** Test tag on the player's cover, for layout tests that place it against the hinge. */
internal const val NOW_PLAYING_COVER_TAG = "now_playing_cover"

// Horizontal screen margin, matching the stacked player.
private val SCREEN_MARGIN = 24.dp

// Clearance either side of the fold, so nothing sits on the crease even when the hinge reports no
// thickness (a seamless fold is a line).
private val FOLD_CLEARANCE = 16.dp

// The cover stays a cover, not a wall, on a large foldable's top half.
private val MAX_COVER_SIZE = 360.dp

// The controls on the lying half keep a hand's width rather than spanning a wide screen.
private val CONTROLS_MAX_WIDTH = 560.dp

/**
 * Now Playing on a foldable half open on a table, split at the hinge.
 *
 * The top half stands up facing the user, so it carries what is read at a distance: the collapse,
 * status and More bar, the flat cover, the chapter and the title. The bottom half lies flat under the
 * hand, so it carries what is touched: the scrubber, the transport (or the state card in its place),
 * and the settings row. Between them is a gap as tall as the hinge, plus
 * [FOLD_CLEARANCE] either side, so nothing straddles the crease.
 *
 * [hingeBounds] are in window pixels. Now Playing fills the window, so they are also this layout's
 * own coordinates. They come from the fold, never from measuring this layout, so drag-to-dismiss and
 * predictive back move the split as one sheet instead of re-flowing it under the finger.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun TabletopNowPlaying(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    hingeBounds: IntRect,
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
    val card = PlayerCards.of(state)
    val (upperHalfHeight, hingeThickness) =
        with(LocalDensity.current) {
            hingeBounds.top.coerceAtLeast(0).toDp() to hingeBounds.height.coerceAtLeast(0).toDp()
        }

    Column(modifier = modifier.fillMaxSize()) {
        // Standing half: read at a distance.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(upperHalfHeight)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ).padding(start = SCREEN_MARGIN, end = SCREEN_MARGIN, bottom = FOLD_CLEARANCE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerTopBar(
                state = state,
                onCollapse = onCollapse,
                onMenuItem = onMenuItem,
                wide = false,
                modifier = Modifier.fillMaxWidth(),
            )

            // The cover takes whatever the bar and the two text lines leave, square and centred.
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PlayerCover(
                    state = state,
                    side = min(min(maxWidth, maxHeight), MAX_COVER_SIZE),
                    modifier = Modifier.testTag(NOW_PLAYING_COVER_TAG),
                )
            }

            Spacer(Modifier.height(16.dp))

            PlayerTitleBlock(
                state = state,
                onContributor = onGoToContributor,
                onShowAuthors = onShowAuthorPicker,
                onShowNarrators = onShowNarratorPicker,
                alignment = Alignment.CenterHorizontally,
                titleLines = 1,
                showsBylines = false,
            )
        }

        // The hinge itself.
        Spacer(Modifier.height(hingeThickness))

        // Lying half: touched.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ).padding(start = SCREEN_MARGIN, end = SCREEN_MARGIN, top = FOLD_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val controlsWidth = Modifier.widthIn(max = CONTROLS_MAX_WIDTH).fillMaxWidth()
            PlayerScrubber(
                progress = progress,
                isPlaying = state.isAudible,
                isBusy = state.isBusy || card is PlayerCard.Problem,
                onSeek = onSeek,
                modifier = controlsWidth,
            )
            if (card != null) {
                PlayerStateCard(card = card, onAction = onCardAction, onRate = onRate, modifier = controlsWidth)
            } else {
                PlayerTransport(
                    state = state,
                    onPrimary = onPrimary,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    playSize = 80.dp,
                    modifier = controlsWidth,
                )
            }
            PlayerSettingsRow(
                speed = state.settings.speed,
                sleepTimer = state.sleepTimer,
                sleepCountdownMs = { SleepCountdowns.remainingMs(state.sleepTimer, progress(), state.settings.speed) },
                showsChapters = true,
                onSpeedClick = onSpeedClick,
                onSleepClick = onSleepClick,
                onChaptersClick = onChaptersClick,
                modifier = controlsWidth,
            )
        }
    }
}
