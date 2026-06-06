package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.features.nowplaying.components.PlayerArtwork
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSecondaryActions
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_narrated_by
import listenup.composeapp.generated.resources.book_detail_other_narrators
import org.jetbrains.compose.resources.stringResource

// Cover scales up to this maximum on wide-compact screens.
private val MAX_COVER_SIZE = 320.dp

// Horizontal screen margin, matching the rest of the player surfaces.
private val SCREEN_MARGIN = 24.dp

/**
 * Compact (phone portrait) full-screen Now Playing layout.
 *
 * Assembles the M3 Expressive player from the Task-5 primitives in a responsive centered [Column].
 * [BoxWithConstraints] is used so the cover and scrubber widths scale fluidly rather than being
 * hardcoded, keeping the layout honest on small phones and large compact tablets alike.
 *
 * The layout is intentionally not yet wired into [NowPlayingScreen] — Task 8 does the adaptive
 * dispatch. This composable just needs to compile cleanly and be public so the Task-8 branch can
 * reference it without import errors.
 *
 * @param state Current [NowPlayingState.Active] snapshot.
 * @param onCollapse Called when the collapse button (or back gesture) fires.
 * @param onPlayPause Called when the play/pause FAB is tapped.
 * @param onSeek Called with a 0f–1f fractional position when the user seeks.
 * @param onSkipBack Called when replay-10 is tapped.
 * @param onSkipForward Called when forward-30 is tapped.
 * @param onPreviousChapter Called when skip-previous is tapped.
 * @param onNextChapter Called when skip-next is tapped.
 * @param onSpeedClick Called when the speed pill is tapped.
 * @param onSleepClick Called when the sleep pill is tapped.
 * @param onChaptersClick Called when the chapters pill is tapped.
 * @param onGoToBook Called when "Go to Book" is selected from the overflow menu.
 * @param onGoToSeries Called with the series id when "Go to Series" is selected.
 * @param onGoToContributor Called with a contributor id when a contributor name or menu item is tapped.
 * @param onShowAuthorPicker Called when the folded author line or "Go to Author…" overflow item is tapped.
 * @param onShowNarratorPicker Called when the folded narrator line or "Go to Narrator…" overflow item is tapped.
 * @param onCloseBook Called when "Close Book" is selected from the overflow menu.
 * @param modifier Optional layout modifier applied to the root [BoxWithConstraints].
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun CompactNowPlaying(
    state: NowPlayingState.Active,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        // Responsive cover size: 80 % of available width, capped at 320 dp.
        val coverSize = min(maxWidth * 0.80f, MAX_COVER_SIZE)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = SCREEN_MARGIN)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            // Top bar: collapse chevron · "NOW PLAYING" · overflow menu.
            PlayerTopBar(
                state = state,
                onCollapse = onCollapse,
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
                onShowAuthorPicker = onShowAuthorPicker,
                onShowNarratorPicker = onShowNarratorPicker,
                onCloseBook = onCloseBook,
                wide = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            // Cover art with ambient glow — responsive size.
            PlayerArtwork(
                coverPath = state.coverPath,
                bookId = state.bookId,
                coverBlurHash = state.coverBlurHash,
                size = coverSize,
            )

            Spacer(Modifier.height(24.dp))

            // Book title — headlineSmall, bold, centered.
            Text(
                text = state.title,
                style =
                    MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            // Chapter line: "Chapter N · Title" or just "Chapter N".
            val chapterLine =
                if (!state.chapterTitle.isNullOrBlank()) {
                    "Chapter ${state.chapterIndex + 1} · ${state.chapterTitle}"
                } else {
                    "Chapter ${state.chapterIndex + 1}"
                }
            Text(
                text = chapterLine,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Narrator line — only shown when narrators are present.
            if (state.narrators.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))

                ClickableContributorLine(
                    contributors = state.narrators,
                    onContributorClick = onGoToContributor,
                    style = MaterialTheme.typography.bodyMedium,
                    nameColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    separatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    prefix = "${stringResource(Res.string.book_detail_narrated_by)} ",
                    foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                    overflowTextRes = Res.string.book_detail_other_narrators,
                    onOverflowClick = onShowNarratorPicker,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Scrubber + time labels — fills column width (horizontal padding from the column).
            PlayerScrubber(
                chapterProgress = state.chapterProgress,
                chapterPositionMs = state.chapterPositionMs,
                chapterDurationMs = state.chapterDurationMs,
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // Transport — slightly smaller FAB (88 dp) per the mobile design reference.
            PlayerTransport(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                fabSize = 88.dp,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            // Secondary actions: speed pill, sleep pill, chapters pill.
            PlayerSecondaryActions(
                playbackSpeed = state.playbackSpeed,
                onSpeedClick = onSpeedClick,
                onSleepClick = onSleepClick,
                onChaptersClick = onChaptersClick,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}
