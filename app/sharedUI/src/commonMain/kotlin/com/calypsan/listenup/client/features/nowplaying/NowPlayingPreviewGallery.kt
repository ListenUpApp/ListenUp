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
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerSettings
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import listenup.composeapp.generated.resources.player_sleep
import listenup.composeapp.generated.resources.player_speed
import org.jetbrains.compose.resources.stringResource

// ── Mock data ───────────────────────────────────────────────────────────────────

private val WIDE_PREVIEW_WIDTH = 1100.dp

private const val MOCK_BOOK_ID = "the-way-of-kings"
private const val MOCK_TITLE = "The Way of Kings"

private val mockAuthors =
    listOf(PlayerContributor(id = "auth-sanderson", name = "Brandon Sanderson", bookCount = 14))

// Four narrators so the fold line ("{lead}, N other narrators") renders in both layouts.
private val mockNarrators =
    listOf(
        PlayerContributor(id = "narr-kramer", name = "Michael Kramer", bookCount = 120),
        PlayerContributor(id = "narr-reading", name = "Kate Reading", bookCount = 98),
        PlayerContributor(id = "narr-vance", name = "Simon Vance", bookCount = 64),
        PlayerContributor(id = "narr-maarleveld", name = "Saskia Maarleveld", bookCount = 41),
    )

private val mockChapterTitles =
    listOf(
        "Prologue: To Kill" to 1_122_000L,
        "Honor Is Dead" to 2_469_000L,
        "The Shattered Plains" to 3_331_000L,
        "The Glory of Ignorance" to 2_238_000L,
        "Bridge Four" to 3_775_000L,
        "Assassin's Mark" to 1_980_000L,
    )

// Six chapters so the UpNextQueue shows a real preview slice + "View all N chapters" footer.
private fun mockChapterRows(current: Int): List<PlayerChapterRow> {
    var start = 0L
    return mockChapterTitles.mapIndexed { index, (title, duration) ->
        PlayerChapterRow(
            index = index,
            number = index + 1,
            title = title,
            startMs = start,
            durationMs = duration,
            isCurrent = index == current,
        ).also {
            start += duration
        }
    }
}

private fun mockChapter(index: Int): PlayerChapter {
    val row = mockChapterRows(index)[index]
    return PlayerChapter(
        index = index,
        number = index + 1,
        total = mockChapterTitles.size,
        title = row.title,
        isGenericTitle = false,
        isWholeBook = false,
        startMs = row.startMs,
        durationMs = row.durationMs,
    )
}

// Primary fixture — playing, chapter 3, 4 narrators.
private val mockActiveState =
    PlayerUiState.Loaded(
        book =
            PlayerBook(
                id = MOCK_BOOK_ID,
                title = MOCK_TITLE,
                authors = mockAuthors,
                narrators = mockNarrators,
                coverPath = null,
                coverHash = null,
                series = listOf(PlayerSeriesRef("ser-stormlight", "The Stormlight Archive", "1")),
                durationMs = 46_800_000L,
                firstPdfDocId = null,
            ),
        phase = PlayerPhase.Playing,
        chapter = mockChapter(2),
        chapters = mockChapterRows(2),
        source = AudioSource.Streaming,
        settings =
            PlayerSettings(
                speed = 1.25f,
                defaultSpeed = 1.0f,
                boostDb = 0f,
                defaultBoostDb = 0f,
                skipBackSec = 10,
                skipForwardSec = 30,
            ),
        sleepTimer = SleepTimerState.Inactive,
        primary = PrimaryControl.Pause,
        showsSkipBack = true,
        canSkipToNextChapter = true,
        finished = null,
    )

// Fast-changing progress fixture matching the primary state's chapter 3 position.
private val mockProgress =
    PlayerProgress(
        chapterPositionMs = 2_031_000L,
        chapterDurationMs = 3_331_000L,
        bookPositionMs = 17_820_000L,
        bookDurationMs = 46_800_000L,
    )

// Buffering fixture — shows the loading spinner inside the scrubber / transport.
private val mockBufferingState =
    mockActiveState.copy(
        phase = PlayerPhase.Buffering,
        chapter = mockChapter(1),
        chapters = mockChapterRows(1),
        primary = PrimaryControl.Busy,
        showsSkipBack = false,
    )

// Progress fixture for the buffering preview — early in chapter 2.
private val mockBufferingProgress =
    mockProgress.copy(
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
                    onPrimary = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onPreviousChapter = {},
                    onNextChapter = {},
                    onSpeedClick = {},
                    onBoostClick = {},
                    onSleepClick = {},
                    onChaptersClick = {},
                    onGoToBook = {},
                    onGoToSeries = {},
                    onGoToContributor = {},
                    onShowAuthorPicker = {},
                    onShowNarratorPicker = {},
                    onCloseBook = {},
                    skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
                    skipForwardSec = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
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
            onPrimary = {},
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onSpeedClick = {},
            onBoostClick = {},
            onSleepClick = {},
            onChaptersClick = {},
            onGoToBook = {},
            onGoToSeries = {},
            onGoToContributor = {},
            onShowAuthorPicker = {},
            onShowNarratorPicker = {},
            onCloseBook = {},
            skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
            skipForwardSec = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
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
            onPrimary = {},
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onSpeedClick = {},
            onBoostClick = {},
            onSleepClick = {},
            onChaptersClick = {},
            onGoToBook = {},
            onGoToSeries = {},
            onGoToContributor = {},
            onShowAuthorPicker = {},
            onShowNarratorPicker = {},
            onCloseBook = {},
            skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
            skipForwardSec = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
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
        onPrimary = {},
        onSkipBack = {},
        skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
    )

    GalleryLabel("Mini-player — phone (buffering)")
    NowPlayingBar(
        state = mockBufferingState,
        progress = { mockBufferingProgress },
        isExpanded = false,
        onTap = {},
        onPrimary = {},
        onSkipBack = {},
        skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
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
            onPrimary = {},
            onSkipBack = {},
            onSkipForward = {},
            onSeek = {},
            onSpeedClick = {},
            skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
            skipForwardSec = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
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
                chapters = mockActiveState.chapters,
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
        modifier = Modifier.padding(horizontal = Spacing.screenMargin),
    )
}
