package com.calypsan.listenup.client.presentation.library

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookMenuPrimaryTest :
    FunSpec({
        test("a started book resumes, carrying its time left") {
            bookMenuPrimary(BookCardStatus.InProgress(fraction = 0.1f, timeLeftMs = 21_720_000L)) shouldBe
                BookMenuPrimary.Resume(timeLeftMs = 21_720_000L)
        }

        test("a finished book is listened to again") {
            bookMenuPrimary(BookCardStatus.Finished(durationMs = 1L)) shouldBe BookMenuPrimary.ListenAgain
        }

        test("an unstarted book, or one with no status yet, plays") {
            bookMenuPrimary(BookCardStatus.NotStarted(durationMs = 1L)) shouldBe BookMenuPrimary.Play
            bookMenuPrimary(null) shouldBe BookMenuPrimary.Play
        }
    })
