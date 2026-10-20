package com.calypsan.listenup.client.presentation.library

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibraryLengthTest :
    FunSpec({
        test("whole days, else whole hours, else whole minutes, else nothing (web's rule, now shared)") {
            libraryLengthOf(41L * 86_400_000 + 3_600_000) shouldBe LibraryLength.Days(41)
            libraryLengthOf(86_400_000) shouldBe LibraryLength.Days(1)
            libraryLengthOf(5L * 3_600_000 + 59 * 60_000) shouldBe LibraryLength.Hours(5)
            libraryLengthOf(59L * 60_000) shouldBe LibraryLength.Minutes(59)
            libraryLengthOf(59_999) shouldBe LibraryLength.None
            libraryLengthOf(0) shouldBe LibraryLength.None
        }
    })
