package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Previous chapter is the one answer every surface reads: a chaptered book always has one (in chapter 1 it restarts
 * the chapter), a chapterless book has none, so its previous control is disabled rather than jumping to 0:00.
 */
class PlayerPreviousChapterTest :
    FunSpec({
        val chapterSix =
            PlayerChapter(5, 6, 75, "Bridge Four", isGenericTitle = false, isWholeBook = false, startMs = 0, durationMs = 1_235_000)
        val wholeBook =
            PlayerChapter(0, 1, 1, "", isGenericTitle = true, isWholeBook = true, startMs = 0, durationMs = 163_800_000)

        test("a chaptered book can skip to the previous chapter, chapter 1 included") {
            loaded(chapterSix).canSkipToPreviousChapter shouldBe true
            loaded(chapterSix.copy(index = 0, number = 1)).canSkipToPreviousChapter shouldBe true
        }

        test("a chapterless book cannot") {
            loaded(wholeBook).canSkipToPreviousChapter shouldBe false
        }
    })

private fun loaded(chapter: PlayerChapter): PlayerUiState.Loaded =
    PlayerUiState.Loaded(
        book = PlayerBook("b1", "The Way of Kings", emptyList(), emptyList(), null, null, emptyList(), 163_800_000, null),
        phase = PlayerPhase.Playing,
        chapter = chapter,
        chapters = emptyList(),
        source = AudioSource.Streaming,
        settings = PlayerSettings(1f, 1f, 0f, 0f, 10, 30),
        sleepTimer = SleepTimerState.Inactive,
        primary = PrimaryControl.Pause,
        showsSkipBack = true,
        canSkipToNextChapter = !chapter.isWholeBook,
        finished = null,
    )
