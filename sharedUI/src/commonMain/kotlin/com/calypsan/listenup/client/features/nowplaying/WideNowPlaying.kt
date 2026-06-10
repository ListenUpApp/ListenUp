package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.features.nowplaying.components.PlayerArtwork
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSecondaryActions
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.features.nowplaying.components.UpNextQueue
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_other_narrators

// Cover size for the wide immersive player pane — matches the design reference (408 dp).
private val WIDE_COVER_SIZE = 408.dp

// Horizontal padding for the full-screen overlay panels.
private val PANEL_HORIZONTAL_PADDING = 32.dp

// Vertical padding at the bottom of the two-pane content row.
private val PANEL_VERTICAL_PADDING_BOTTOM = 32.dp

// Gap between the left and right panes.
private val PANE_GAP = 24.dp

// Right pane maximum width — keeps the queue readable at large viewport widths.
private val QUEUE_MAX_WIDTH = 420.dp

// Padding inside the left immersive player card.
private val PLAYER_CARD_PADDING = 40.dp

/**
 * Wide (expanded / desktop) two-pane Now Playing layout.
 *
 * Renders the M3 Expressive immersive player + "Up next" queue side-by-side below a
 * desktop-style header bar:
 * - **Header** ([PlayerTopBar] with `wide = true`): tonal collapse button, "Now playing" overline
 *   + book/series subtitle column, cast icon, overflow menu.
 * - **Left pane** ([weight(1f)]): [surfaceContainerLow] card (28 dp corners, 40 dp padding)
 *   with ambient glow, 408 dp cover art ([PlayerArtwork]), title, chapter label, narrator
 *   ([ClickableContributorLine]), [PlayerScrubber], [PlayerTransport] (96 dp FAB), and
 *   [PlayerSecondaryActions].
 * - **Right pane** ([widthIn(max=420.dp)]): [UpNextQueue] with a chapter preview slice and
 *   a "View all N chapters" footer.
 *
 * This composable is intentionally **not** called by [NowPlayingScreen] — Task 8 wires the
 * adaptive dispatch. It must compile cleanly and be public so the Task-8 branch can import it
 * without errors.
 *
 * @param state Current [NowPlayingState.Active] snapshot.
 * @param progress Fast-changing playback progress driving the scrubber and time labels.
 * @param onCollapse Called when the collapse button is tapped.
 * @param onPlayPause Called when the play/pause FAB is tapped.
 * @param onSeek Called with a 0f–1f fractional position when the user seeks.
 * @param onSkipBack Called when replay-10 is tapped.
 * @param onSkipForward Called when forward-30 is tapped.
 * @param onPreviousChapter Called when skip-previous is tapped.
 * @param onNextChapter Called when skip-next is tapped.
 * @param onSpeedClick Called when the speed pill is tapped.
 * @param onSleepClick Called when the sleep pill is tapped.
 * @param onChaptersClick Called when the chapters pill or "View all chapters" footer is tapped.
 * @param onSeekToChapter Called with the zero-based chapter index when a queue row is tapped.
 *   Task 8 maps this to the ViewModel's [seekToChapter] action.
 * @param onGoToBook Called when "Go to Book" is selected from the overflow menu.
 * @param onGoToSeries Called with the series id when "Go to Series" is selected.
 * @param onGoToContributor Called with a contributor id when a contributor name or menu item is tapped.
 * @param onShowAuthorPicker Called when the folded author line or "Go to Author…" is tapped.
 * @param onShowNarratorPicker Called when the folded narrator line or "Go to Narrator…" is tapped.
 * @param onCloseBook Called when "Close Book" is selected from the overflow menu.
 * @param modifier Optional layout modifier applied to the root [Surface].
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun WideNowPlaying(
    state: NowPlayingState.Active,
    progress: PlaybackProgress,
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
    onSeekToChapter: (Int) -> Unit,
    onGoToBook: () -> Unit,
    onGoToSeries: (String) -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowAuthorPicker: () -> Unit,
    onShowNarratorPicker: () -> Unit,
    onCloseBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Desktop-style header: tonal collapse · "Now playing"/title · cast · overflow.
            PlayerTopBar(
                state = state,
                onCollapse = onCollapse,
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
                onShowAuthorPicker = onShowAuthorPicker,
                onShowNarratorPicker = onShowNarratorPicker,
                onCloseBook = onCloseBook,
                wide = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PANEL_HORIZONTAL_PADDING),
            )

            // Two-pane content row.
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = PANEL_HORIZONTAL_PADDING,
                            end = PANEL_HORIZONTAL_PADDING,
                            bottom = PANEL_VERTICAL_PADDING_BOTTOM,
                            top = 4.dp,
                        ),
                horizontalArrangement = Arrangement.spacedBy(PANE_GAP),
            ) {
                // LEFT: Immersive player card — fills remaining width.
                ImmersivePlayerPane(
                    state = state,
                    progress = progress,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSpeedClick = onSpeedClick,
                    onSleepClick = onSleepClick,
                    onChaptersClick = onChaptersClick,
                    onGoToContributor = onGoToContributor,
                    onShowNarratorPicker = onShowNarratorPicker,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                )

                // RIGHT: "Up next" queue — fluid up to 420 dp max.
                UpNextQueue(
                    chapters = state.chapters,
                    currentChapterIndex = state.chapterIndex,
                    totalChapters = state.totalChapters,
                    onSeekToChapter = onSeekToChapter,
                    onViewAllChapters = onChaptersClick,
                    modifier =
                        Modifier
                            .widthIn(max = QUEUE_MAX_WIDTH)
                            .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Left immersive player pane for the wide two-pane layout.
 *
 * A [surfaceContainerLow] card with 28 dp rounded corners and 40 dp internal padding.
 * Content (scrollable for very small heights):
 * - Soft [primaryContainer] glow + 408 dp cover art via [PlayerArtwork].
 * - Book title (headlineMedium, bold, centred).
 * - Chapter label: "Chapter N · Title" (or "Chapter N" when no title).
 * - Narrator line via [ClickableContributorLine] (only when narrators are present).
 * - [PlayerScrubber] with elapsed / remaining labels.
 * - [PlayerTransport] with the 96 dp FAB from the design reference.
 * - [PlayerSecondaryActions]: speed, sleep, chapters pills.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ImmersivePlayerPane(
    state: NowPlayingState.Active,
    progress: PlaybackProgress,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowNarratorPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(PLAYER_CARD_PADDING)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Cover art with ambient glow — design reference size 408 dp.
            PlayerArtwork(
                coverPath = state.coverPath,
                bookId = state.bookId,
                coverBlurHash = state.coverBlurHash,
                size = WIDE_COVER_SIZE,
                title = state.title,
                author = state.author,
            )

            Spacer(Modifier.height(30.dp))

            // Book title — headlineMedium, bold, centred.
            Text(
                text = state.title,
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))

            // Chapter label: "Chapter N · Title" or just "Chapter N".
            val chapterLine =
                if (!state.chapterTitle.isNullOrBlank()) {
                    "Chapter ${state.chapterIndex + 1} · ${state.chapterTitle}"
                } else {
                    "Chapter ${state.chapterIndex + 1}"
                }
            Text(
                text = chapterLine,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Narrator line — only when narrators are present.
            if (state.narrators.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))

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
                    foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                    overflowTextRes = Res.string.book_detail_other_narrators,
                    onOverflowClick = onShowNarratorPicker,
                )
            }

            // Spacer separates the metadata from the controls so controls sit near the bottom.
            Spacer(Modifier.height(24.dp))

            // Scrubber: wavy seek bar + elapsed / remaining labels.
            PlayerScrubber(
                chapterProgress = progress.chapterProgress,
                chapterPositionMs = progress.chapterPositionMs,
                chapterDurationMs = progress.chapterDurationMs,
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(22.dp))

            // Transport row with the 96 dp FAB from the design reference.
            PlayerTransport(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                fabSize = 96.dp,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // Secondary actions: speed pill, sleep pill, chapters pill.
            PlayerSecondaryActions(
                playbackSpeed = state.playbackSpeed,
                onSpeedClick = onSpeedClick,
                onSleepClick = onSleepClick,
                onChaptersClick = onChaptersClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
