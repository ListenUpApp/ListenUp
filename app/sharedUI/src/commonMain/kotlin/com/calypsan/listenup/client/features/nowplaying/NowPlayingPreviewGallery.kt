package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.nowplaying.components.CastConnectedButton
import com.calypsan.listenup.client.presentation.player.ContributorPickerType
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.FinishedInfo
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

// ── Mock data ───────────────────────────────────────────────────────────────────


private const val LARGE_FONT_SCALE = 1.5f

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

// Six chapters for the phone states; the wide previews play deep in the 75-chapter book (mockLongBookChapters).
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

/** A long book's 75 chapters, for the chapter sheet opening centred deep in the list. */
private fun mockLongBookChapters(current: Int): List<PlayerChapterRow> =
    (0 until 75).map { index ->
        PlayerChapterRow(
            index = index,
            number = index + 1,
            title = if (index == current) "Bridge Four" else "Chapter ${index + 1}",
            startMs = index * 2_184_000L,
            durationMs = 2_184_000L,
            isCurrent = index == current,
        )
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

// The cases Now Playing's states take on a phone: the transport, or the card in its place (P32).
private val mockFinished =
    FinishedInfo(
        finishedAtMs = 1_791_651_600_000L,
        startedAtMs = 1_788_652_800_000L,
        durationMs = 46_800_000L,
        yourHalfStars = 8,
        nextBook =
            NextInSeries(
                bookId = "words-of-radiance",
                title = "Words of Radiance",
                coverPath = null,
                coverHash = null,
                narratorNames = null,
                seriesName = "The Stormlight Archive",
                sequenceLabel = "2",
                durationMs = 172_800_000L,
            ),
    )

private val compactCases: List<Pair<String, PlayerUiState.Loaded>> =
    listOf(
        "Compact — playing, 4 narrators (fold shows)" to mockActiveState,
        "Compact — paused, sleep armed" to
            mockActiveState.copy(
                phase = PlayerPhase.Paused,
                primary = PrimaryControl.Play,
                sleepTimer =
                    SleepTimerState.Active(
                        mode = SleepTimerMode.Duration(30),
                        remainingMs = 1_421_000,
                        totalMs = 1_800_000,
                        startedAt = 0,
                    ),
            ),
        "Compact — buffering (contained loading indicator)" to mockBufferingState,
        "Compact — a failure (error card)" to
            mockActiveState.copy(
                phase = PlayerPhase.Failed(PlaybackError.ConnectionLost(correlationId = "req-7f3a")),
                primary = PrimaryControl.Retry,
                showsSkipBack = false,
            ),
        "Compact — offline, not downloaded (neutral card)" to
            mockActiveState.copy(
                phase = PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded()),
                primary = PrimaryControl.Retry,
                source = AudioSource.Offline,
                showsSkipBack = false,
            ),
        "Compact — held, admin (held card)" to
            mockActiveState.copy(
                phase = PlayerPhase.Unavailable(BookError.HeldForReview()),
                primary = PrimaryControl.OpenInbox,
                source = AudioSource.NotPlayable,
                showsSkipBack = false,
            ),
        "Compact — finished, next in series" to
            mockActiveState.copy(
                phase = PlayerPhase.Finished,
                primary = PrimaryControl.ListenAgain,
                finished = mockFinished,
            ),
        "Compact — finished, no next book" to
            mockActiveState.copy(
                phase = PlayerPhase.Finished,
                primary = PrimaryControl.ListenAgain,
                finished = mockFinished.copy(nextBook = null),
            ),
        "Compact — chapterless (Whole book)" to
            mockActiveState.copy(
                chapter = mockChapter(0).copy(title = "", isGenericTitle = true, isWholeBook = true),
            ),
        "Compact — casting" to mockActiveState.copy(source = AudioSource.Casting("Living Room")),
    )

// ── Gallery ─────────────────────────────────────────────────────────────────────

/**
 * On-device gallery of the redesigned Now Playing components rendered with mock data — a
 * fallback for validating the player without a live server or real playback session. Launched
 * by the debug [PreviewGalleryActivity] via `--es gallery nowplaying`; not part of the
 * navigation graph.
 *
 * Cover art renders as a grey placeholder (coverPath = null). Every phone state is here: playing, paused with the
 * sleep countdown, buffering, each card tone, finished with and without a next book, chapterless, casting and a
 * large font, then the wide layout, the mini players and the panels.
 */
@Composable
fun NowPlayingPreviewGallery() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            compactCases.forEach { (label, state) ->
                CompactCase(label, state)
                HorizontalDivider()
            }

            CompactCase("Compact — large font (settings row wraps)", mockActiveState, fontScale = LARGE_FONT_SCALE)

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
private fun CompactCase(
    label: String,
    state: PlayerUiState.Loaded,
    fontScale: Float? = null,
) {
    GalleryLabel(label)
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
        Box(modifier = Modifier.fillMaxWidth().height(860.dp)) {
            CompactNowPlaying(
                state = state,
                progress = { if (state.phase == PlayerPhase.Buffering) mockBufferingProgress else mockProgress },
                onCollapse = {},
                onPrimary = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onPreviousChapter = {},
                onNextChapter = {},
                onSpeedClick = {},
                onSleepClick = {},
                onChaptersClick = {},
                onGoToContributor = {},
                onShowAuthorPicker = {},
                onShowNarratorPicker = {},
                onMenuItem = {},
                onCardAction = {},
                onRate = {},
            )
        }
    }
}

/** The wide previews' window sizes (spec §5.3): three columns, an upright tablet's column and panel, and no panel. */
private val wideWindows =
    listOf(
        "1280×800 — three columns" to (1280.dp to 800.dp),
        "900×1200 — the player column beside the panel" to (900.dp to 1200.dp),
        "1000×700 — cover and controls, Chapters as a sheet" to (1000.dp to 700.dp),
    )

/** The 75-chapter book, playing chapter 38, "Bridge Four", so the panel opens centred deep in its list. */
private val mockWideState =
    mockActiveState.copy(
        chapter =
            mockChapter(2).copy(
                index = 37,
                number = 38,
                total = 75,
                title = "Bridge Four",
                startMs = 37 * 2_184_000L,
                durationMs = 2_184_000L,
            ),
        chapters = mockLongBookChapters(37),
    )

@Composable
private fun WideSection() {
    wideWindows.forEach { (label, size) ->
        listOf(false, true).forEach { dark ->
            WideCase("Wide $label — playing, ${if (dark) "dark" else "light"} →", mockWideState, size, dark)
        }
    }
    val threeColumns = wideWindows.first().second
    WideCase(
        "Wide 1280×800 — a failure (the card in the transport's place; the panel stays) →",
        mockWideState.copy(
            phase = PlayerPhase.Failed(PlaybackError.ConnectionLost(correlationId = "req-7f3a")),
            primary = PrimaryControl.Retry,
            showsSkipBack = false,
        ),
        threeColumns,
        dark = false,
    )
    WideCase(
        "Wide 1280×800 — chapterless (no panel, nothing to pick), dark →",
        mockActiveState.copy(
            chapter = mockChapter(0).copy(title = "", isGenericTitle = true, isWholeBook = true),
            chapters = emptyList(),
        ),
        threeColumns,
        dark = true,
    )
}

@Composable
private fun WideCase(
    label: String,
    state: PlayerUiState.Loaded,
    size: Pair<Dp, Dp>,
    dark: Boolean,
) {
    GalleryLabel(label)
    WidePreview(width = size.first, height = size.second) {
        ListenUpTheme(darkTheme = dark, dynamicColor = false) {
            WideNowPlaying(
                state = state,
                progress = { mockProgress },
                onCollapse = {},
                onPrimary = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onPreviousChapter = {},
                onNextChapter = {},
                onSpeedClick = {},
                onSleepClick = {},
                onChaptersClick = {},
                onSeekToChapter = {},
                onGoToContributor = {},
                onShowAuthorPicker = {},
                onShowNarratorPicker = {},
                onMenuItem = {},
                onCardAction = {},
                onRate = {},
            )
        }
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
        onKeepListening = {},
        skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
        modifier = Modifier.fillMaxWidth(),
    )

    GalleryLabel("Mini-player — phone (buffering)")
    NowPlayingBar(
        state = mockBufferingState,
        progress = { mockBufferingProgress },
        isExpanded = false,
        onTap = {},
        onPrimary = {},
        onSkipBack = {},
        onKeepListening = {},
        skipBackwardSec = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MiniPlayerDesktopSection() {
    GalleryLabel("Mini-player — desktop/tablet — scroll horizontally →")
    WidePreview {
        NowPlayingToolbar(
            state = mockActiveState,
            progress = { mockProgress },
            isExpanded = false,
            onTap = {},
            onPrimary = {},
            onSkipBack = {},
            onSkipForward = {},
            onSeek = {},
            onSpeedClick = {},
            onSleepClick = {},
            onChaptersClick = {},
            onKeepListening = {},
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
private fun WidePreview(
    width: Dp = 1100.dp,
    height: Dp = 820.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(modifier = Modifier.width(width).height(height)) { content() }
    }
}

/** Every sheet state the gallery opens (PR 4), each in light or dark. */
private enum class PanelPreview(
    val label: String,
) {
    Speed("Speed"),
    SleepIdle("Sleep"),
    SleepWholeBook("Sleep · whole book"),
    SleepRunning("Sleep · running"),
    SleepEndOfChapter("Sleep · end of chapter"),
    SleepFading("Sleep · fading"),
    Boost("Boost"),
    Chapters("Chapters · 38 of 75"),
    Narrators("Narrators"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelsSection() {
    var open by remember { mutableStateOf<PanelPreview?>(null) }
    var dark by remember { mutableStateOf(false) }
    GalleryLabel("Panels — tap to open (adaptive: bottom sheet on phone, dialog on a wide window)")
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = dark, onClick = { dark = !dark }, label = { Text(DARK_TOGGLE_LABEL) })
        PanelPreview.entries.forEach { panel -> Button(onClick = { open = panel }) { Text(panel.label) } }
    }
    GalleryLabel("Cast while casting — light, then dark")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(false, true).forEach { darkChip ->
            ListenUpTheme(darkTheme = darkChip, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    CastConnectedButton(
                        deviceName = "Living Room",
                        onOpenRoutes = {},
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
    open?.let { panel ->
        ListenUpTheme(darkTheme = dark, dynamicColor = false) {
            PanelPreviewSheet(panel = panel, onDismiss = { open = null })
        }
    }
}

@Composable
private fun PanelPreviewSheet(
    panel: PanelPreview,
    onDismiss: () -> Unit,
) {
    val thirtyMinutes =
        SleepTimerState.Active(
            mode = SleepTimerMode.Duration(30),
            remainingMs = 1_421_000,
            totalMs = 1_800_000,
            startedAt = 0,
        )
    val endOfChapter =
        SleepTimerState.Active(mode = SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0)
    when (panel) {
        PanelPreview.Speed -> {
            PlaybackSpeedSheet(
                currentSpeed = 1.25f,
                defaultSpeed = 1.0f,
                bookRemainingAtSpeedMs = { 116_640_000 },
                onSpeedChange = {},
                onStep = {},
                onResetToDefault = {},
                onDismiss = onDismiss,
            )
        }

        PanelPreview.SleepIdle -> {
            SleepPreview(mockSleepSheetState(SleepTimerState.Inactive), onDismiss)
        }

        PanelPreview.SleepWholeBook -> {
            SleepPreview(
                mockSleepSheetState(SleepTimerState.Inactive).copy(
                    chapter = mockChapter(0).copy(title = "", isGenericTitle = true, isWholeBook = true),
                ),
                onDismiss,
            )
        }

        PanelPreview.SleepRunning -> {
            SleepPreview(mockSleepSheetState(thirtyMinutes), onDismiss)
        }

        PanelPreview.SleepEndOfChapter -> {
            SleepPreview(mockSleepSheetState(endOfChapter), onDismiss)
        }

        PanelPreview.SleepFading -> {
            SleepPreview(mockSleepSheetState(SleepTimerState.FadingOut), onDismiss)
        }

        PanelPreview.Boost -> {
            VolumeBoostSheet(
                currentBoostDb = 6f,
                defaultBoostDb = 0f,
                onBoostChange = {},
                onResetToDefault = {},
                onDismiss = onDismiss,
            )
        }

        PanelPreview.Chapters -> {
            ChapterPickerSheet(
                chapters = mockLongBookChapters(current = 37),
                playingLeft = { "8m" },
                onChapterSelected = {},
                onDismiss = onDismiss,
            )
        }

        PanelPreview.Narrators -> {
            PlayerContributorPicker(
                type = ContributorPickerType.NARRATORS,
                book = mockActiveState.book,
                onContributorClick = {},
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun SleepPreview(
    state: SleepSheetState,
    onDismiss: () -> Unit,
) {
    SleepTimerSheet(
        state = state,
        onSetTimer = {},
        onCancelTimer = {},
        onExtendTimer = {},
        onKeepListening = {},
        onDismiss = onDismiss,
    )
}

/** The sleep sheet over the mock book, 8 minutes from the end of its chapter, at 11:18 PM. */
private fun mockSleepSheetState(timer: SleepTimerState): SleepSheetState =
    SleepSheetState(
        timer = timer,
        chapter = mockActiveState.chapter,
        chapterRemainingMs = { 480_000 },
        countdownMs = { if (timer is SleepTimerState.Active && timer.remainingMs > 0) timer.remainingMs else 480_000 },
        meterFraction = { 0.79f },
        nowMs = { MOCK_NOW_MS },
    )

/** The gallery's own switch between the light and dark schemes for the panels; gallery chrome, not app copy. */
private const val DARK_TOGGLE_LABEL = "Dark"

/** 23:18:19 UTC: "Pauses at" reads late evening in the previews. */
private const val MOCK_NOW_MS = (23 * 3_600 + 18 * 60 + 19) * 1_000L

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
