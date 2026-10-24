package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SleepTimerChoicesTest :
    FunSpec({
        fun chapter(isWholeBook: Boolean) =
            PlayerChapter(
                index = 0,
                number = 1,
                total = if (isWholeBook) 1 else 75,
                title = if (isWholeBook) "" else "Prologue",
                isGenericTitle = isWholeBook,
                isWholeBook = isWholeBook,
                startMs = 0,
                durationMs = 1_200_000,
            )

        test("the ladder every platform offers, in order") {
            SleepTimerChoices.DURATION_MINUTES shouldBe listOf(15, 30, 45, 60, 120)
            SleepTimerChoices.EXTEND_MINUTES shouldBe listOf(5, 10, 15)
        }

        test("End of chapter is offered for a chaptered book, never for a whole-book one") {
            SleepTimerChoices.offersEndOfChapter(chapter(isWholeBook = false)) shouldBe true
            SleepTimerChoices.offersEndOfChapter(chapter(isWholeBook = true)) shouldBe false
        }

        test("only a duration can be extended") {
            SleepTimerChoices.canExtend(SleepTimerState.Active(SleepTimerMode.Duration(30), 1, 1, 0)) shouldBe true
            SleepTimerChoices.canExtend(SleepTimerState.Active(SleepTimerMode.EndOfChapter, 0, 0, 0)) shouldBe false
            SleepTimerChoices.canExtend(SleepTimerState.Inactive) shouldBe false
            SleepTimerChoices.canExtend(SleepTimerState.FadingOut) shouldBe false
        }

        test("the custom stepper starts at 20 and moves in fives within 5..720") {
            SleepTimerChoices.CUSTOM_DEFAULT_MINUTES shouldBe 20
            SleepTimerChoices.stepCustom(20, up = true) shouldBe 25
            SleepTimerChoices.stepCustom(20, up = false) shouldBe 15
            SleepTimerChoices.stepCustom(5, up = false) shouldBe 5
            SleepTimerChoices.stepCustom(720, up = true) shouldBe 720
            SleepTimerChoices.stepCustom(23, up = true) shouldBe 25 // a typed value snaps onto the ladder
            SleepTimerChoices.stepCustom(23, up = false) shouldBe 20
        }

        test("a typed custom value is clamped into 1..720, and nonsense is refused") {
            SleepTimerChoices.parseCustom("37") shouldBe 37
            SleepTimerChoices.parseCustom(" 37 ") shouldBe 37
            SleepTimerChoices.parseCustom("0") shouldBe 1
            SleepTimerChoices.parseCustom("9000") shouldBe 720
            SleepTimerChoices.parseCustom("") shouldBe null
            SleepTimerChoices.parseCustom("ten") shouldBe null
        }
    })
