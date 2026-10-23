package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class SleepCountdownsTest :
    FunSpec({
        // 8 min left in a 20-min chapter.
        val progress =
            PlayerProgress(
                chapterPositionMs = 720_000,
                chapterDurationMs = 1_200_000,
                bookPositionMs = 10_000_000,
                bookDurationMs = 163_800_000,
            )
        val duration = SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 1_421_000, totalMs = 1_800_000, startedAt = 0)
        val endOfChapter = SleepTimerState.Active(SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0)

        test("a duration timer counts its own wall-clock remainder") {
            SleepCountdowns.remainingMs(duration, progress, speed = 1.5f) shouldBe 1_421_000
        }

        test("an end-of-chapter timer counts the chapter's remainder at the listening speed, never zero") {
            SleepCountdowns.remainingMs(endOfChapter, progress, speed = 1.0f) shouldBe 480_000
            SleepCountdowns.remainingMs(endOfChapter, progress, speed = 1.5f) shouldBe 320_000
        }

        test("no timer, or a fade under way, has no countdown") {
            SleepCountdowns.remainingMs(SleepTimerState.Inactive, progress, 1f) shouldBe null
            SleepCountdowns.remainingMs(SleepTimerState.FadingOut, progress, 1f) shouldBe null
        }

        test("the meter shows what is left: a duration's share of its total, a chapter's share of itself") {
            SleepCountdowns.meterFraction(duration, progress) shouldBe (1_421_000f / 1_800_000f plusOrMinus 0.0001f)
            SleepCountdowns.meterFraction(endOfChapter, progress) shouldBe (0.4f plusOrMinus 0.0001f)
            SleepCountdowns.meterFraction(SleepTimerState.Inactive, progress) shouldBe 0f
        }

        test("the primitive overload Swift calls agrees with the typed one") {
            SleepCountdowns.remainingMs(
                isEndOfChapter = true,
                durationRemainingMs = 0,
                chapterRemainingMs = 480_000,
                speed = 2f,
            ) shouldBe 240_000
        }

        test("a zero or negative speed never divides by zero") {
            SleepCountdowns.remainingMs(endOfChapter, progress, speed = 0f) shouldBe 480_000
        }
    })
