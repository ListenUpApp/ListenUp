package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibraryLettersTest :
    FunSpec({
        fun book(
            title: String,
            author: String = "",
            series: String? = null,
        ) = BookListItem(
            id = BookId(title),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = title,
            authors = if (author.isEmpty()) emptyList() else listOf(BookContributor("a-$author", author)),
            narrators = emptyList(),
            duration = 1L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
            series = series?.let { listOf(BookSeries(seriesId = "s-$it", seriesName = it, sequence = 1.0)) }.orEmpty(),
        )

        test("runs of one letter become sections with their first index and size") {
            letterSections(listOf("Alpha", "Amber", "Mist", "Zed")) { it.first() } shouldBe
                listOf(LetterSection("A", 0, 2), LetterSection("M", 2, 1), LetterSection("Z", 3, 1))
        }

        test("an empty list has no sections") {
            letterSections(emptyList<String>()) { it.first() } shouldBe emptyList()
        }

        test("a letter that recurs (numeric series first, unseriesed books last) keeps both runs, in list order") {
            letterSections(listOf("#1", "Bravo", "#2")) { it.first() } shouldBe
                listOf(LetterSection("#", 0, 1), LetterSection("B", 1, 1), LetterSection("#", 2, 1))
        }

        test("Ignore articles files The Way of Kings under W on a title sort, and under T without it") {
            val letterOf = requireNotNull(bookLetterOf(SortCategory.TITLE, ignoreArticles = true))
            letterOf(book("The Way of Kings")) shouldBe 'W'
            requireNotNull(bookLetterOf(SortCategory.TITLE, ignoreArticles = false))(book("The Way of Kings")) shouldBe 'T'
        }

        test("author and series sorts file by the name the sort used, never stripping an article") {
            requireNotNull(bookLetterOf(SortCategory.AUTHOR, ignoreArticles = true))(book("x", author = "The Band")) shouldBe 'T'
            requireNotNull(bookLetterOf(SortCategory.SERIES, ignoreArticles = true))(book("x", series = "Dune")) shouldBe 'D'
            requireNotNull(bookLetterOf(SortCategory.SERIES, ignoreArticles = true))(book("x")) shouldBe '#'
        }

        test("a non-text sort has no letters") {
            listOf(SortCategory.DURATION, SortCategory.YEAR, SortCategory.ADDED, SortCategory.RATING, SortCategory.LISTENER_RATING)
                .forEach { bookLetterOf(it, ignoreArticles = true) shouldBe null }
        }

        test("series by name honour Ignore articles; contributors by name never strip") {
            requireNotNull(seriesLetterOf(SortCategory.NAME, ignoreArticles = true))("The Wandering Inn") shouldBe 'W'
            seriesLetterOf(SortCategory.BOOK_COUNT, ignoreArticles = true) shouldBe null
            requireNotNull(contributorLetterOf(SortCategory.NAME))("the Rolling Stones") shouldBe 'T'
            contributorLetterOf(SortCategory.BOOK_COUNT) shouldBe null
        }
    })
