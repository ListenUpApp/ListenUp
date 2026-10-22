package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState

// Horizontal screen margin, matching the rest of the player surfaces.
private val SCREEN_MARGIN = 24.dp

// Below this available height the fixed controls can't all fit, so the layout scrolls rather than
// clipping the transport off screen (e.g. compact landscape phones). Above it, the layout fits and
// stays scroll-free.
private val COMPACT_FIT_MIN_HEIGHT = 640.dp

/**
 * Compact (phone portrait) full-screen Now Playing (spec §5.2, board `NP-AndroidPhone`), top to bottom: the top bar
 * (Collapse · status · More), the book ([PlayerBookBlock]), the chapter scrubber with the book sentence between its
 * times, **the transport, or the state card in its place** (P32, C5), and the settings row.
 *
 * [BoxWithConstraints] scales the cover by both width and available height, and weighted gaps distribute slack so the
 * transport and settings always stay on screen. A non-scrolling layout also leaves the parent's swipe-down-to-collapse
 * gesture (in [NowPlayingScreen]) free to fire. While a problem card shows the scrubber holds still; on a finished
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
        val fits = maxHeight >= COMPACT_FIT_MIN_HEIGHT
        // Capped by available height as well as width, so the controls below fit without scrolling.
        val coverSize = min(min(maxWidth * 0.82f, PlayerCoverSide), maxHeight * 0.40f)
        val card = PlayerCards.of(state)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = SCREEN_MARGIN)
                    .then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState())),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerTopBar(
                state = state,
                onCollapse = onCollapse,
                onMenuItem = onMenuItem,
                modifier = Modifier.fillMaxWidth(),
            )
            FlexibleGap(weight = 1f, fallback = 16.dp, fits = fits)
            PlayerBookBlock(
                state = state,
                coverSide = coverSize,
                onContributor = onGoToContributor,
                onShowAuthors = onShowAuthorPicker,
                onShowNarrators = onShowNarratorPicker,
            )
            FlexibleGap(weight = 1.4f, fallback = 24.dp, fits = fits)
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
 * A vertical gap that expands to fill slack when the layout [fits] (weighted), or collapses to a
 * [fallback] fixed height in the scrolling fallback — where `weight` modifiers aren't valid because
 * a `verticalScroll` parent measures children with unbounded height.
 */
@Composable
private fun ColumnScope.FlexibleGap(
    weight: Float,
    fallback: Dp,
    fits: Boolean,
) {
    if (fits) {
        Spacer(Modifier.weight(weight))
    } else {
        Spacer(Modifier.height(fallback))
    }
}
