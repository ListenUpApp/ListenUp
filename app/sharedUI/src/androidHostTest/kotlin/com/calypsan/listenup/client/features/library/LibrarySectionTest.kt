package com.calypsan.listenup.client.features.library

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibrarySectionTest :
    FunSpec({
        test("the four sections are Books, Series, Authors and Narrators, in that order") {
            LibrarySection.entries shouldBe
                listOf(LibrarySection.Books, LibrarySection.Series, LibrarySection.Authors, LibrarySection.Narrators)
        }

        test("a saved section restores by name") {
            LibrarySectionSaver.restore("Narrators") shouldBe LibrarySection.Narrators
        }

        test("a saved In progress section from an older build restores to Books rather than crashing") {
            LibrarySectionSaver.restore("InProgress") shouldBe LibrarySection.Books
        }
    })
