package com.calypsan.listenup.client.presentation.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun titled(vararg titles: String): List<Chapter> =
    titles.mapIndexed { i, title -> Chapter(id = "c$i", title = title, duration = 1L, startTime = i.toLong()) }

/**
 * What the editor works out for itself about markers: which chapters look like parts, and what a
 * new marker should be called.
 *
 * Both exist to save typing, so both are only ever offers. What these pin is where the offer stops:
 * a book that already has structure is left alone, and a chapter that merely starts with "Part" is
 * not one.
 */
class ChapterMarkersTest :
    FunSpec({

        listOf(
            "Part 1",
            "Part 12",
            "Part I",
            "Part IV",
            "Part XIV",
            "Part XXXIX",
            "Part One",
            "Part Twelve",
            "Part Twenty",
            "Part Twenty-One",
            "Part Twenty One",
            "PART TWO",
            "part iii",
            "  Part 3  ",
        ).forEach { title ->
            test("'$title' looks like a part") { title.looksLikePartTitle() shouldBe true }
        }

        listOf(
            "Part",
            "Part ",
            "Part Two: The Reckoning",
            "Part of the Plan",
            "Partly Cloudy",
            "A Part 1",
            "Part 1 2",
            "Part IIII",
            "Part VX",
            "Part C",
            "Part D",
            "Part L",
            "Part M",
            "Part XL",
            "Part XXXX",
            "Part Mix",
            "Part Twentyone",
            "Chapter 1",
            "Book 1",
        ).forEach { title ->
            test("'$title' does not look like a part") { title.looksLikePartTitle() shouldBe false }
        }

        test("two or more Part chapters earn the suggestion, in book order") {
            titled("Credits", "Part I", "1", "Part II", "2").partSuggestion().shouldNotBeNull().titles shouldBe
                listOf("Part I", "Part II")
        }

        test("one Part chapter does not: a single match is a coincidence, not a structure") {
            titled("Credits", "Part I", "1").partSuggestion() shouldBe null
        }

        test("a book with any marker is left alone") {
            val withPart = titled("Part I", "Part II").withPartMarker("c0", "Part I")
            val withBook = titled("Part I", "Part II").withBookMarker("c0", "Book One")

            withClue("the reader has started structuring it themselves") {
                withPart.partSuggestion() shouldBe null
                withBook.partSuggestion() shouldBe null
            }
        }

        test("accepting names each part after its own chapter, trimmed, and touches nothing else") {
            val accepted = titled("Credits", "  Part I ", "1", "Part II").withSuggestedParts()

            accepted.map { it.partTitle } shouldBe listOf(null, "Part I", null, "Part II")
        }

        test("accepting with nothing to suggest is no edit") {
            val chapters = titled("Credits", "Part I")

            chapters.withSuggestedParts() shouldBe chapters
        }

        // ── default names ────────────────────────────────────────────────────────

        test("a new part on a chapter that looks like one takes the chapter's title") {
            titled("Part Three").defaultMarkerTitle("c0", ChapterMarkerTier.Part, "Part") shouldBe "Part Three"
        }

        test("a new book never borrows a Part-looking title — that would misname the tier") {
            titled("Part Three").defaultMarkerTitle("c0", ChapterMarkerTier.Book, "Book") shouldBe "Book 1"
        }

        test("otherwise it is the next number after the markers already in the book") {
            val chapters =
                titled("a", "b", "c")
                    .withPartMarker("c0", "Opening")
                    .withPartMarker("c1", "Middle")

            chapters.defaultMarkerTitle("c2", ChapterMarkerTier.Part, "Part") shouldBe "Part 3"
        }

        test("a number already taken is skipped, ignoring case") {
            val chapters =
                titled("a", "b", "c")
                    .withPartMarker("c0", "PART 2")

            chapters.defaultMarkerTitle("c2", ChapterMarkerTier.Part, "Part") shouldBe "Part 3"
        }

        test("the tiers count separately") {
            val chapters = titled("a", "b").withPartMarker("c0", "Part 1")

            chapters.defaultMarkerTitle("c1", ChapterMarkerTier.Book, "Book") shouldBe "Book 1"
        }

        test("each tier reads and answers for its own field") {
            val chapter = Chapter("c", "t", 1L, 0L, partTitle = "P", bookTitle = "B")

            ChapterMarkerTier.Part.titleOn(chapter) shouldBe "P"
            ChapterMarkerTier.Book.titleOn(chapter) shouldBe "B"
        }

        test("the move targets are the chapters not already opening that tier") {
            val chapters =
                titled("a", "b", "c")
                    .withPartMarker("c0", "Part 1")
                    .withBookMarker("c1", "Book 1")

            chapters.moveTargets(ChapterMarkerTier.Part).map { it.id } shouldBe listOf("c1", "c2")
            chapters.moveTargets(ChapterMarkerTier.Book).map { it.id } shouldBe listOf("c0", "c2")
        }
    })
