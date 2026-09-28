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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.calypsan.listenup.client.features.nowplaying.components.PlayerArtwork
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSecondaryActions
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress

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
 * The top half stands up facing the user, so it carries what is read at a distance: the collapse
 * and overflow bar, the cover (large, and shadowed like every cover), the title and the chapter. The
 * bottom half lies flat under the hand, so it carries what is touched: the scrubber, the transport,
 * and the speed, boost, sleep and chapter actions. Between them is a gap as tall as the hinge, plus
 * [FOLD_CLEARANCE] either side, so nothing straddles the crease.
 *
 * [hingeBounds] are in window pixels. Now Playing fills the window, so they are also this layout's
 * own coordinates. They come from the fold, never from measuring this layout, so drag-to-dismiss and
 * predictive back move the split as one sheet instead of re-flowing it under the finger.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun TabletopNowPlaying(
    state: NowPlayingState.Active,
    progress: () -> PlaybackProgress,
    hingeBounds: IntRect,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedClick: () -> Unit,
    onBoostClick: () -> Unit,
    onSleepClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
    skipBackwardSec: Int,
    skipForwardSec: Int,
    hasPdf: Boolean = false,
    onOpenPdf: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
                onShowAuthorPicker = onShowAuthorPicker,
                onShowNarratorPicker = onShowNarratorPicker,
                onCloseBook = onCloseBook,
                hasPdf = hasPdf,
                onOpenPdf = onOpenPdf,
                wide = false,
                modifier = Modifier.fillMaxWidth(),
            )

            // The cover takes whatever the bar and the two text lines leave, square and centred.
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PlayerArtwork(
                    coverPath = state.coverPath,
                    bookId = state.bookId,
                    size = min(min(maxWidth, maxHeight), MAX_COVER_SIZE),
                    title = state.title,
                    author = state.author,
                    coverHash = state.coverHash,
                    modifier = Modifier.testTag(NOW_PLAYING_COVER_TAG),
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            // The chapter's own title, falling back to "Chapter N" only when it's genuinely untitled
            // (the same line the stacked and side-by-side players show).
            val chapterLine =
                state.chapterTitle?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${state.chapterIndex + 1}"
            Text(
                text = chapterLine,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onSeek = onSeek,
                modifier = controlsWidth,
            )
            PlayerTransport(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                skipBackwardSec = skipBackwardSec,
                skipForwardSec = skipForwardSec,
                fabSize = 80.dp,
                modifier = controlsWidth,
            )
            PlayerSecondaryActions(
                playbackSpeed = state.playbackSpeed,
                onSpeedClick = onSpeedClick,
                volumeBoostDb = state.volumeBoostDb,
                onBoostClick = onBoostClick,
                onSleepClick = onSleepClick,
                onChaptersClick = onChaptersClick,
                modifier = controlsWidth,
            )
        }
    }
}
