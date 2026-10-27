package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.isBusy
import com.calypsan.listenup.client.features.nowplaying.components.PlayerCover
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSettingsRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerStateCard
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTitleBlock
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import com.calypsan.listenup.client.presentation.player.SleepCountdowns

/** Test tag on the reader's listening pane. */
internal const val READER_LISTENING_PANE_TAG = "reader_listening_pane"

/** The pane's cover stays small: the page is what is read here. */
private val PANE_COVER_SIZE = 160.dp

/** The pane's main button: as large as the width allows within these, a step down from Now Playing's 96dp. */
private val PANE_PLAY_SIZE_MIN = 56.dp
private val PANE_PLAY_SIZE_MAX = 80.dp

/**
 * The transport's width less its main button: two 48dp chapter buttons, two 56dp skips, four 8dp gaps. The button
 * group cannot measure narrower than its items, so the main button gives way first.
 */
private val TRANSPORT_WIDTH_BESIDE_PLAY = 240.dp

/** Horizontal margin inside the pane, kept small so the transport fits half of a narrow book fold (about 320dp). */
private val PANE_MARGIN = 8.dp

/**
 * What the reader's strip and listening pane do when touched: each forwards to the [PlayerViewModel] action Now Playing
 * uses for the same control ([readerPlayerActions]).
 */
internal data class ReaderPlayerActions(
    val onPrimary: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSpeedClick: () -> Unit,
    val onSleepClick: () -> Unit,
    val onKeepListening: () -> Unit,
    val onCardAction: (PlayerCardAction) -> Unit,
    val onRate: (Int) -> Unit,
)

/** The reader's actions on this view model — the same ones `NowPlayingHost` hands Now Playing. */
internal fun PlayerViewModel.readerPlayerActions(): ReaderPlayerActions =
    ReaderPlayerActions(
        onPrimary = ::onPrimary,
        onSeek = ::seekWithinChapter,
        onSkipBack = { skipBack() },
        onSkipForward = { skipForward() },
        onPreviousChapter = ::previousChapter,
        onNextChapter = ::nextChapter,
        onSpeedClick = ::showSpeedPicker,
        onSleepClick = ::showSleepTimer,
        onKeepListening = ::cancelSleepTimer,
        onCardAction = ::onCardAction,
        onRate = ::rate,
    )

/**
 * The reader's listening pane, beside the page when the screen has room ([ReaderLayout.Beside]): a small cover, the
 * title and chapter, the scrubber, the transport (or the state card in its place) and the settings row — Now Playing's
 * own pieces, as [com.calypsan.listenup.client.features.nowplaying.TabletopNowPlaying] assembles them. It takes the
 * strip's place, so the two never show together. Bylines are left out: their names are links that leave the reader.
 *
 * @param progress reads the playback position; only the scrubber and the sleep countdown call it, so a position tick
 *   redraws those leaves and nothing else in the pane.
 */
@Composable
internal fun ReaderListeningPane(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    actions: ReaderPlayerActions,
    modifier: Modifier = Modifier,
) {
    val card = PlayerCards.of(state)
    Surface(
        modifier = modifier.fillMaxSize().testTag(READER_LISTENING_PANE_TAG),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PANE_MARGIN, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerCover(state = state, side = PANE_COVER_SIZE)
            PlayerTitleBlock(
                state = state,
                onContributor = {},
                onShowAuthors = {},
                onShowNarrators = {},
                alignment = Alignment.CenterHorizontally,
                titleLines = 2,
                showsBylines = false,
            )
            PlayerScrubber(
                progress = progress,
                isBusy = state.isBusy || card is PlayerCard.Problem,
                onSeek = actions.onSeek,
                modifier = Modifier.fillMaxWidth(),
            )
            if (card != null) {
                PlayerStateCard(
                    card = card,
                    onAction = actions.onCardAction,
                    onRate = actions.onRate,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    PlayerTransport(
                        state = state,
                        onPrimary = actions.onPrimary,
                        onSkipBack = actions.onSkipBack,
                        onSkipForward = actions.onSkipForward,
                        onPreviousChapter = actions.onPreviousChapter,
                        onNextChapter = actions.onNextChapter,
                        playSize =
                            (maxWidth - TRANSPORT_WIDTH_BESIDE_PLAY).coerceIn(
                                PANE_PLAY_SIZE_MIN,
                                PANE_PLAY_SIZE_MAX,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            PlayerSettingsRow(
                speed = state.settings.speed,
                sleepTimer = state.sleepTimer,
                sleepCountdownMs = { SleepCountdowns.remainingMs(state.sleepTimer, progress(), state.settings.speed) },
                showsChapters = false,
                onSpeedClick = actions.onSpeedClick,
                onSleepClick = actions.onSleepClick,
                onChaptersClick = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
