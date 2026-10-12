package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSettings
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl

/** A loaded book for the player's UI tests: chapter 6 of 75 of a long book, two narrators. */
internal fun loadedPlayerState(
    title: String = "The Way of Kings",
    phase: PlayerPhase = PlayerPhase.Playing,
    primary: PrimaryControl = PrimaryControl.Pause,
    chapterTitle: String = "Bridge Four",
    coverPath: String? = null,
    skipBackSec: Int = 10,
    skipForwardSec: Int = 30,
): PlayerUiState.Loaded =
    PlayerUiState.Loaded(
        book =
            PlayerBook(
                id = "book-1",
                title = title,
                authors = listOf(PlayerContributor("a1", "Brandon Sanderson", 14)),
                narrators =
                    listOf(
                        PlayerContributor("n1", "Michael Kramer", 120),
                        PlayerContributor("n2", "Kate Reading", 98),
                    ),
                coverPath = coverPath,
                coverHash = null,
                series = emptyList(),
                durationMs = 163_800_000,
                firstPdfDocId = null,
            ),
        phase = phase,
        chapter =
            PlayerChapter(
                index = 5,
                number = 6,
                total = 75,
                title = chapterTitle,
                isGenericTitle = false,
                isWholeBook = false,
                startMs = 0,
                durationMs = 1_235_000,
            ),
        chapters =
            (0 until 75).map {
                PlayerChapterRow(it, it + 1, "Chapter ${it + 1}", it * 1_000L, 1_000L, isCurrent = it == 5)
            },
        source = AudioSource.Streaming,
        settings = PlayerSettings(1.0f, 1.0f, 0f, 0f, skipBackSec, skipForwardSec),
        sleepTimer = SleepTimerState.Inactive,
        primary = primary,
        showsSkipBack = phase == PlayerPhase.Playing || phase == PlayerPhase.Paused,
        canSkipToNextChapter = true,
        finished = null,
    )

/** A position partway through chapter 6. */
internal val samplePlayerProgress =
    PlayerProgress(
        chapterPositionMs = 724_000,
        chapterDurationMs = 1_235_000,
        bookPositionMs = 18_000_000,
        bookDurationMs = 163_800_000,
    )
