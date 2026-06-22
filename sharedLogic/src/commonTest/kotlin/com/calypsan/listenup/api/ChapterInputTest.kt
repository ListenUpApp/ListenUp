package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ChapterInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChapterInputTest :
    FunSpec({
        test("valid input constructs") {
            ChapterInput(id = "ch1", title = "Prologue", startTime = 0, duration = 1000).startTime shouldBe 0L
        }
        test("blank title is rejected") {
            shouldThrow<IllegalArgumentException> { ChapterInput(id = "ch1", title = "  ", startTime = 0, duration = 1000) }
        }
        test("blank id is rejected") {
            shouldThrow<IllegalArgumentException> { ChapterInput(id = " ", title = "A", startTime = 0, duration = 1000) }
        }
        test("negative startTime is rejected") {
            shouldThrow<IllegalArgumentException> { ChapterInput(id = "ch1", title = "A", startTime = -1, duration = 1000) }
        }
        test("negative duration is rejected") {
            shouldThrow<IllegalArgumentException> { ChapterInput(id = "ch1", title = "A", startTime = 0, duration = -1) }
        }
        test("title longer than MAX_TITLE is rejected") {
            shouldThrow<IllegalArgumentException> {
                ChapterInput(id = "ch1", title = "A".repeat(ChapterInput.MAX_TITLE + 1), startTime = 0, duration = 1000)
            }
        }
        test("blank partTitle is rejected") {
            shouldThrow<IllegalArgumentException> {
                ChapterInput(id = "ch1", title = "Chapter 1", startTime = 0, duration = 1000, partTitle = "  ")
            }
        }
        test("blank bookTitle is rejected") {
            shouldThrow<IllegalArgumentException> {
                ChapterInput(id = "ch1", title = "Chapter 1", startTime = 0, duration = 1000, bookTitle = "  ")
            }
        }
        test("non-blank header titles are accepted") {
            val input =
                ChapterInput(
                    id = "ch1",
                    title = "Chapter 1",
                    startTime = 0,
                    duration = 1000,
                    partTitle = "Part One",
                    bookTitle = "Book I",
                )
            input.partTitle shouldBe "Part One"
            input.bookTitle shouldBe "Book I"
        }
    })
