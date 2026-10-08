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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import com.calypsan.listenup.client.playback.PlaybackProgress
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_other_narrators

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
 * Wide (expanded / foldable inner display) Now Playing layout — a landscape player.
 *
 * On a foldable's wide-but-short inner display, stacking a big cover over the controls forces a
 * scroll just to reach play/pause. This layout instead places everything side-by-side below a
 * desktop-style header, so the whole player fits on one screen with no scrolling:
 * - **Header** ([PlayerTopBar] with `wide = true`): tonal collapse button, "Now playing" overline
 *   + book/series subtitle, cast icon, overflow menu.
 * - **Left pane**: the cover art ([PlayerArtwork]) sized off the *available height* so it stays
 *   genuinely square and never gets clamped to a portrait sliver, vertically centred.
 * - **Right pane**: title, chapter label, narrator ([ClickableContributorLine]), [PlayerScrubber],
 *   [PlayerTransport] (96 dp FAB) and [PlayerSecondaryActions] (speed · sleep · chapters),
 *   vertically centred and left-aligned so it reads as a landscape player, not a centred phone one.
 *
 * Chapter browsing lives behind the "Chapters" pill in [PlayerSecondaryActions] (the same control
 * the phone layout uses), so no dedicated queue pane is needed at this size. A queue column is the
 * planned addition for true desktop widths (≥ 1200 dp), reusing `UpNextQueue`.
 *
 * On a short window — a phone on its side, 360–410 dp tall — the header drops to the phone bar, the
 * secondary actions move under the cover, and the controls tighten (see [WideControls]), so the
 * transport still fits without scrolling. Should a window be shorter still, the controls column
 * scrolls rather than clipping the transport.
 *
 * @param state Current [NowPlayingState.Active] snapshot.
 * @param progress Fast-changing playback progress driving the scrubber and time labels.
 * @param onCollapse Called when the collapse button is tapped.
 * @param onPlayPause Called when the play/pause FAB is tapped.
 * @param onSeek Called with a 0f–1f fractional position when the user seeks.
 * @param onSkipBack Called when skip-back is tapped.
 * @param onSkipForward Called when skip-forward is tapped.
 * @param onPreviousChapter Called when skip-previous is tapped.
 * @param onNextChapter Called when skip-next is tapped.
 * @param onSpeedClick Called when the speed pill is tapped.
 * @param onBoostClick Called when the boost pill is tapped.
 * @param onSleepClick Called when the sleep pill is tapped.
 * @param onChaptersClick Called when the chapters pill is tapped.
 * @param onGoToBook Called when "Go to Book" is selected from the overflow menu.
 * @param onGoToSeries Called with the series id when "Go to Series" is selected.
 * @param onGoToContributor Called with a contributor id when a contributor name or menu item is tapped.
 * @param onShowAuthorPicker Called when the folded author line or "Go to Author…" is tapped.
 * @param onShowNarratorPicker Called when the folded narrator line or "Go to Narrator…" is tapped.
 * @param onCloseBook Called when "Close Book" is selected from the overflow menu.
 * @param skipBackwardSec The user's configured backward skip, in seconds.
 * @param skipForwardSec The user's configured forward skip, in seconds.
 * @param modifier Optional layout modifier applied to the root [Surface].
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun WideNowPlaying(
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
                onGoToBook = onGoToBook,
                onGoToSeries = onGoToSeries,
                onGoToContributor = onGoToContributor,
                onShowAuthorPicker = onShowAuthorPicker,
                onShowNarratorPicker = onShowNarratorPicker,
                onCloseBook = onCloseBook,
                hasPdf = hasPdf,
                onOpenPdf = onOpenPdf,
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
                    // Speed pill, boost pill, sleep pill, chapters pill.
                    PlayerSecondaryActions(
                        playbackSpeed = state.playbackSpeed,
                        onSpeedClick = onSpeedClick,
                        volumeBoostDb = state.volumeBoostDb,
                        onBoostClick = onBoostClick,
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
                        PlayerArtwork(
                            coverPath = state.coverPath,
                            bookId = state.bookId,
                            size = coverSize,
                            title = state.title,
                            author = state.author,
                            coverHash = state.coverHash,
                        )
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
                                onPlayPause = onPlayPause,
                                onSeek = onSeek,
                                onSkipBack = onSkipBack,
                                onSkipForward = onSkipForward,
                                onPreviousChapter = onPreviousChapter,
                                onNextChapter = onNextChapter,
                                onGoToContributor = onGoToContributor,
                                onShowNarratorPicker = onShowNarratorPicker,
                                skipBackwardSec = skipBackwardSec,
                                skipForwardSec = skipForwardSec,
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
 * The right pane's title, chapter, narrator, scrubber and transport. When [isShort] (a phone on its
 * side) the title drops to one line, the narrator line goes (it stays in the overflow menu), and the
 * transport shrinks to a 64 dp FAB.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WideControls(
    state: NowPlayingState.Active,
    progress: () -> PlaybackProgress,
    isShort: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onGoToContributor: (String) -> Unit,
    onShowNarratorPicker: () -> Unit,
    skipBackwardSec: Int,
    skipForwardSec: Int,
) {
    // Book title — headlineMedium, bold; one titleLarge line when short.
    Text(
        text = state.title,
        style =
            (if (isShort) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
                .copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (isShort) 1 else 2,
        overflow = TextOverflow.Ellipsis,
    )

    Spacer(Modifier.height(if (isShort) 4.dp else 8.dp))

    // The chapter's own title, falling back to "Chapter N" only when it's genuinely untitled. No
    // "Chapter N · " prefix — the title is often itself "Chapter N", which produced confusing
    // duplicates like "Chapter 3 · Chapter 1".
    val chapterLine =
        state.chapterTitle?.takeIf { it.isNotBlank() }
            ?: "Chapter ${state.chapterIndex + 1}"
    Text(
        text = chapterLine,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    // Narrator line — only when narrators are present, and when there's room.
    if (state.narrators.isNotEmpty() && !isShort) {
        Spacer(Modifier.height(8.dp))

        ClickableContributorLine(
            contributors = state.narrators,
            onContributorClick = onGoToContributor,
            style = MaterialTheme.typography.bodyMedium,
            nameColor = MaterialTheme.colorScheme.onSurfaceVariant,
            separatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
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

    Spacer(Modifier.height(if (isShort) 12.dp else 32.dp))

    // Scrubber: wavy seek bar + elapsed / remaining labels.
    PlayerScrubber(
        progress = progress,
        isPlaying = state.isPlaying,
        isBuffering = state.isBuffering,
        onSeek = onSeek,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(if (isShort) 8.dp else 24.dp))

    // Transport row with the 96 dp FAB from the design reference (64 dp when short).
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
        fabSize = if (isShort) 64.dp else 96.dp,
        ctrlSize = if (isShort) 48.dp else 60.dp,
        modifier = Modifier.fillMaxWidth(),
    )
}
