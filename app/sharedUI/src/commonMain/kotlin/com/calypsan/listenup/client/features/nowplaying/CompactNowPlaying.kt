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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.features.nowplaying.components.PlayerArtwork
import com.calypsan.listenup.client.features.nowplaying.components.PlayerSecondaryActions
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTransport
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_narrated_by
import listenup.composeapp.generated.resources.book_detail_other_narrators
import org.jetbrains.compose.resources.stringResource

// Cover scales up to this maximum on wide-compact screens.
private val MAX_COVER_SIZE = 320.dp

// Horizontal screen margin, matching the rest of the player surfaces.
private val SCREEN_MARGIN = 24.dp

// Below this available height the fixed controls can't all fit, so the layout scrolls rather than
// clipping the transport off screen (e.g. compact landscape phones). Above it, the layout fits and
// stays scroll-free.
private val COMPACT_FIT_MIN_HEIGHT = 640.dp

/**
 * Compact (phone portrait) full-screen Now Playing layout.
 *
 * Assembles the M3 Expressive player from the player primitives in a responsive centered [Column].
 * [BoxWithConstraints] is used so the cover scales fluidly by both width and available height, and
 * weighted spacers distribute slack so the transport and secondary actions always stay on screen —
 * the layout fits without scrolling on small phones and tall foldable inner displays alike. A
 * non-scrolling layout also leaves the parent's swipe-down-to-collapse gesture (in [NowPlayingScreen])
 * free to fire, instead of being consumed by an inner scroll container.
 *
 * @param state Current [NowPlayingState.Active] snapshot.
 * @param progress Deferred read of the fast-changing playback progress driving the scrubber and
 *   time labels. Passed as a lambda so a position tick recomposes only the scrubber, not this layout.
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
    progress: () -> PlaybackProgress,
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
    hasPdf: Boolean = false,
    onOpenPdf: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        // On tall screens (phone portrait, foldable inner displays) the layout fits without
        // scrolling, which also leaves the parent swipe-to-collapse gesture unobstructed. On short
        // screens (compact landscape) it scrolls so the transport is never clipped off — never stranded.
        val fits = maxHeight >= COMPACT_FIT_MIN_HEIGHT

        // Cover is capped by available height as well as width, so on tall screens the controls below
        // fit without scrolling (width alone would let the cover grow tall enough to push them off).
        val coverSize = min(min(maxWidth * 0.82f, MAX_COVER_SIZE), maxHeight * 0.40f)

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = SCREEN_MARGIN)
                    .then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState())),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                hasPdf = hasPdf,
                onOpenPdf = onOpenPdf,
                wide = false,
                modifier = Modifier.fillMaxWidth(),
            )

            // Flexible gap: absorbs slack above the cover so the metadata cluster sits in the upper
            // half on tall screens while the controls anchor near the bottom.
            FlexibleGap(weight = 1f, fallback = 16.dp, fits = fits)

            // Cover art with ambient glow — responsive size.
            PlayerArtwork(
                coverPath = state.coverPath,
                bookId = state.bookId,
                size = coverSize,
                title = state.title,
                author = state.author,
                coverHash = state.coverHash,
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

            // The chapter's own title, falling back to "Chapter N" only when it's genuinely
            // untitled. No "Chapter N · " prefix — the title is often itself "Chapter N", which
            // produced confusing duplicates like "Chapter 3 · Chapter 1" (offset by front-matter).
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

            // Flexible gap below the metadata — slightly larger than the top gap so the cluster
            // reads as upper-middle and the transport group anchors toward the bottom edge.
            FlexibleGap(weight = 1.4f, fallback = 24.dp, fits = fits)

            // Scrubber + time labels — fills column width (horizontal padding from the column).
            PlayerScrubber(
                progress = progress,
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // Transport — slightly smaller FAB (88 dp).
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
