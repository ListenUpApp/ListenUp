package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.BookSentence
import com.calypsan.listenup.client.features.nowplaying.components.PlayerBookBlock
import com.calypsan.listenup.client.features.nowplaying.components.PlayerCoverSide
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSettingsRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerStateCard
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

// Horizontal screen margin, matching the rest of the player surfaces.
private val SCREEN_MARGIN = 24.dp

/**
 * Compact (phone portrait) full-screen Now Playing (spec §5.2, board `NP-AndroidPhone`), top to bottom: the top bar
 * (Collapse · status · More), the book ([PlayerBookBlock]), the chapter scrubber with the book sentence between its
 * times, **the transport, or the state card in its place** (P32, C5), and the settings row.
 *
 * [BoxWithConstraints] scales the cover by both width and available height, and weighted gaps distribute slack. When
 * the content is taller than the screen (large text, a card) it scrolls; when it fits the scroll is off, which leaves
 * the parent's swipe-down-to-collapse gesture (in [NowPlayingScreen]) free to fire. While a problem card shows the scrubber holds still; on a finished
 * book it stays live (§4.5: a seek there is Listen again from that point).
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun CompactNowPlaying(
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
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        // Capped by available height as well as width, so the controls below fit without scrolling where they can.
        val coverSize = min(min(maxWidth * 0.82f, PlayerCoverSide), maxHeight * 0.40f)
        val card = PlayerCards.of(state)
        val scroll = rememberScrollState()

        // At least the screen's height, so the weighted gaps share any slack; taller content (large text, a card) scrolls.
        // The scroll is enabled only when there is somewhere to go, so a screen that fits leaves the swipe down to
        // collapse to the parent.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = scroll.maxValue > 0)
                    .heightIn(min = maxHeight)
                    .padding(horizontal = SCREEN_MARGIN),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerTopBar(
                state = state,
                onCollapse = onCollapse,
                onMenuItem = onMenuItem,
                modifier = Modifier.fillMaxWidth(),
            )
            FlexibleGap(weight = 1f, fallback = 16.dp)
            PlayerBookBlock(
                state = state,
                coverSide = coverSize,
                onContributor = onGoToContributor,
                onShowAuthors = onShowAuthorPicker,
                onShowNarrators = onShowNarratorPicker,
            )
            FlexibleGap(weight = 1.4f, fallback = 24.dp)
            PlayerScrubber(
                progress = progress,
                isBusy = state.isBusy || card is PlayerCard.Problem,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
                center = { BookSentence(progress) },
            )
            Spacer(Modifier.height(Spacing.xl))
            if (card != null) {
                PlayerStateCard(
                    card = card,
                    onAction = onCardAction,
                    onRate = onRate,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PlayerTransport(
                    state = state,
                    onPrimary = onPrimary,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
            PlayerSettingsRow(
                speed = state.settings.speed,
                sleepTimer = state.sleepTimer,
                castingTo = (state.source as? AudioSource.Casting)?.deviceName,
                sleepCountdownMs = { SleepCountdowns.remainingMs(state.sleepTimer, progress(), state.settings.speed) },
                showsChapters = true,
                onSpeedClick = onSpeedClick,
                onSleepClick = onSleepClick,
                onChaptersClick = onChaptersClick,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A vertical gap of at least [fallback] that grows by [weight] to share slack. Inside the scroll a Column measures its
 * weights against its minimum height — the screen's — so on a tall screen the gaps spread the content, and when the
 * content is taller they are just [fallback] and the screen scrolls.
 */
@Composable
private fun ColumnScope.FlexibleGap(
    weight: Float,
    fallback: Dp,
) {
    Spacer(Modifier.height(fallback))
    Spacer(Modifier.weight(weight))
}
