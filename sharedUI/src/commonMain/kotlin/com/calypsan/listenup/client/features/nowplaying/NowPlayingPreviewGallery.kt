package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.playback.NowPlayingChapter
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import com.calypsan.listenup.client.playback.SleepTimerState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import listenup.composeapp.generated.resources.player_sleep
import listenup.composeapp.generated.resources.player_speed
import org.jetbrains.compose.resources.stringResource

// ── Mock data ───────────────────────────────────────────────────────────────────

private val WIDE_PREVIEW_WIDTH = 1100.dp

private const val MOCK_BOOK_ID = "the-way-of-kings"
private const val MOCK_TITLE = "The Way of Kings"

private const val ROLE_AUTHOR = "Author"
private const val ROLE_NARRATOR = "Narrator"

private val mockAuthors =
    listOf(BookContributor(id = "auth-sanderson", name = "Brandon Sanderson", roles = listOf(ROLE_AUTHOR)))

// Four narrators so the fold line ("{lead}, N other narrators") renders in both layouts.
private val mockNarrators =
    listOf(
        BookContributor(id = "narr-kramer", name = "Michael Kramer", roles = listOf(ROLE_NARRATOR)),
        BookContributor(id = "narr-reading", name = "Kate Reading", roles = listOf(ROLE_NARRATOR)),
        BookContributor(id = "narr-vance", name = "Simon Vance", roles = listOf(ROLE_NARRATOR)),
        BookContributor(id = "narr-maarleveld", name = "Saskia Maarleveld", roles = listOf(ROLE_NARRATOR)),
    )

// Six chapters so the UpNextQueue shows a real preview slice + "View all N chapters" footer.
private val mockChapters =
    listOf(
        NowPlayingChapter(index = 0, title = "Prologue: To Kill", durationMs = 1_122_000L),
        NowPlayingChapter(index = 1, title = "Honor Is Dead", durationMs = 2_469_000L),
        NowPlayingChapter(index = 2, title = "The Shattered Plains", durationMs = 3_331_000L),
        NowPlayingChapter(index = 3, title = "The Glory of Ignorance", durationMs = 2_238_000L),
        NowPlayingChapter(index = 4, title = "Bridge Four", durationMs = 3_775_000L),
        NowPlayingChapter(index = 5, title = "Assassin's Mark", durationMs = 1_980_000L),
    )

// Domain chapters for the Chapters panel preview (the panel consumes List<Chapter>, not NowPlayingChapter).
private val mockDomainChapters =
    mockChapters.map { chapter ->
        Chapter(
            id = "chapter-${chapter.index}",
            title = chapter.title,
            duration = chapter.durationMs,
            startTime = 0L,
        )
    }

// Primary fixture — playing, chapter 2, 4 narrators.
private val mockActiveState =
    NowPlayingState.Active(
        bookId = MOCK_BOOK_ID,
        title = MOCK_TITLE,
        author = "Brandon Sanderson",
        coverPath = null,
        coverHash = null,
        coverBlurHash = null,
        authors = mockAuthors,
        narrators = mockNarrators,
        seriesId = "ser-stormlight",
        seriesName = "The Stormlight Archive",
        chapterTitle = "The Shattered Plains",
        chapterIndex = 2,
        totalChapters = mockChapters.size,
        chapters = mockChapters,
        isPlaying = true,
        isBuffering = false,
        playbackSpeed = 1.25f,
        defaultPlaybackSpeed = 1.0f,
    )

// Fast-changing progress fixture matching the primary state's chapter 2 position.
private val mockProgress =
    PlaybackProgress(
        bookProgress = 0.38f,
        bookPositionMs = 17_820_000L,
        bookDurationMs = 46_800_000L,
        chapterProgress = 0.61f,
        chapterPositionMs = 2_031_000L,
        chapterDurationMs = 3_331_000L,
    )

// Buffering fixture — shows the loading spinner inside the scrubber / transport.
private val mockBufferingState =
    mockActiveState.copy(
        chapterTitle = "Honor Is Dead",
        chapterIndex = 1,
        isPlaying = false,
        isBuffering = true,
    )

// Progress fixture for the buffering preview — early in chapter 1.
private val mockBufferingProgress =
    mockProgress.copy(
        chapterProgress = 0.12f,
        chapterPositionMs = 296_000L,
        chapterDurationMs = 2_469_000L,
    )

// ── Gallery ─────────────────────────────────────────────────────────────────────

/**
 * On-device gallery of the redesigned Now Playing components rendered with mock data — a
 * fallback for validating the player without a live server or real playback session. Launched
 * by the debug [PreviewGalleryActivity] via `--es gallery nowplaying`; not part of the
 * navigation graph.
 *
 * Cover art renders as a grey placeholder (coverPath = null); the ambient glow, scrubber
 * animation, transport controls, and Up Next queue are what this gallery verifies.
 */
@Composable
fun NowPlayingPreviewGallery() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            CompactSection()

            HorizontalDivider()

            GalleryLabel("Compact — buffering spinner")
            Box(modifier = Modifier.fillMaxWidth().height(820.dp)) {
                CompactNowPlaying(
                    state = mockBufferingState,
                    progress = { mockBufferingProgress },
                    onCollapse = {},
                    onPlayPause = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onPreviousChapter = {},
                    onNextChapter = {},
                    onSpeedClick = {},
                    onStorySoFarClick = {},
                    onSleepClick = {},
                    onChaptersClick = {},
                    onGoToBook = {},
                    onGoToSeries = {},
                    onGoToContributor = {},
                    onShowAuthorPicker = {},
                    onShowNarratorPicker = {},
                    onCloseBook = {},
                )
            }

            HorizontalDivider()

            WideSection()

            HorizontalDivider()

            MiniPlayerPhoneSection()

            HorizontalDivider()

            MiniPlayerDesktopSection()

            HorizontalDivider()

            PanelsSection()

            HorizontalDivider()
        }
    }
}

@Composable
private fun CompactSection() {
    GalleryLabel("Compact — playing, 4 narrators (fold shows), chapter scrubber")
    Box(modifier = Modifier.fillMaxWidth().height(820.dp)) {
        CompactNowPlaying(
            state = mockActiveState,
            progress = { mockProgress },
            onCollapse = {},
            onPlayPause = {},
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onSpeedClick = {},
            onStorySoFarClick = {},
            onSleepClick = {},
            onChaptersClick = {},
            onGoToBook = {},
            onGoToSeries = {},
            onGoToContributor = {},
            onShowAuthorPicker = {},
            onShowNarratorPicker = {},
            onCloseBook = {},
        )
    }
}

@Composable
private fun WideSection() {
    GalleryLabel("Wide (expanded / foldable inner) — landscape player — scroll horizontally →")
    WidePreview {
        WideNowPlaying(
            state = mockActiveState,
            progress = { mockProgress },
            onCollapse = {},
            onPlayPause = {},
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onSpeedClick = {},
            onStorySoFarClick = {},
            onSleepClick = {},
            onChaptersClick = {},
            onGoToBook = {},
            onGoToSeries = {},
            onGoToContributor = {},
            onShowAuthorPicker = {},
            onShowNarratorPicker = {},
            onCloseBook = {},
        )
    }
}

@Composable
private fun MiniPlayerPhoneSection() {
    GalleryLabel("Mini-player — phone (playing)")
    NowPlayingBar(
        state = mockActiveState,
        progress = { mockProgress },
        isExpanded = false,
        onTap = {},
        onPlayPause = {},
        onSkipBack = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )

    GalleryLabel("Mini-player — phone (buffering)")
    NowPlayingBar(
        state = mockBufferingState,
        progress = { mockBufferingProgress },
        isExpanded = false,
        onTap = {},
        onPlayPause = {},
        onSkipBack = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun MiniPlayerDesktopSection() {
    GalleryLabel("Mini-player — desktop/tablet — scroll horizontally →")
    WidePreview {
        DockedNowPlayingBar(
            state = mockActiveState,
            progress = { mockProgress },
            isExpanded = false,
            onTap = {},
            onPlayPause = {},
            onSkipBack = {},
            onSkipForward = {},
            onSeek = {},
            onSpeedClick = {},
        )
    }
}

/**
 * Renders a width-constrained (tablet / desktop) component at its intended width inside a
 * horizontal scroller, so wide-only layouts don't collapse when viewed on a narrow phone.
 */
@Composable
private fun WidePreview(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(modifier = Modifier.width(WIDE_PREVIEW_WIDTH).height(820.dp)) { content() }
    }
}

@Composable
private fun PanelsSection() {
    var open by remember { mutableStateOf<String?>(null) }
    GalleryLabel("Panels — tap to open (adaptive: bottom sheet on phone, dialog on a wide window)")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = { open = "speed" }) { Text(stringResource(Res.string.player_speed)) }
        Button(onClick = { open = "chapters" }) { Text(stringResource(Res.string.player_chapters)) }
        Button(onClick = { open = "sleep" }) { Text(stringResource(Res.string.player_sleep)) }
    }
    when (open) {
        "speed" -> {
            PlaybackSpeedSheet(
                currentSpeed = 1.25f,
                defaultSpeed = 1.0f,
                onSpeedChange = {},
                onResetToDefault = {},
                onDismiss = { open = null },
            )
        }

        "chapters" -> {
            ChapterPickerSheet(
                chapters = mockDomainChapters,
                currentChapterIndex = 2,
                onChapterSelected = {},
                onDismiss = { open = null },
            )
        }

        "sleep" -> {
            SleepTimerSheet(
                currentState = SleepTimerState.Inactive,
                onSetTimer = {},
                onCancelTimer = {},
                onExtendTimer = {},
                onDismiss = { open = null },
            )
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────

@Composable
private fun GalleryLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
