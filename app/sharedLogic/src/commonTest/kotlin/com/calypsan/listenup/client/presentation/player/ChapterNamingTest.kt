package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChapterNamingTest :
    FunSpec({
        test("a titled chapter is named by the book's own title, generic or not") {
            ChapterNaming.of(title = "Bridge Four", number = 6, isWholeBook = false) shouldBe
                ChapterName.Titled("Bridge Four")
            ChapterNaming.of(title = "Chapter 14", number = 16, isWholeBook = false) shouldBe
                ChapterName.Titled("Chapter 14")
        }

        test("an untitled chapter falls back to its number") {
            ChapterNaming.of(title = "  ", number = 6, isWholeBook = false) shouldBe ChapterName.Untitled(6)
            ChapterNaming.of(title = null, number = 2, isWholeBook = false) shouldBe ChapterName.Untitled(2)
        }

        test("a chapterless book is the whole book") {
            ChapterNaming.of(title = "The Way of Kings", number = 1, isWholeBook = true) shouldBe ChapterName.WholeBook
        }
    })
