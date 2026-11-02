package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibrarySortTest :
    FunSpec({
        val asc = SortDirection.ASCENDING
        val desc = SortDirection.DESCENDING

        fun book(
            title: String,
            author: String = "Author",
            durationMs: Long = 1L,
            year: Int? = 2000,
            addedAt: Long = 0L,
            series: String? = null,
            sequence: Double? = null,
        ) = BookListItem(
            id = BookId(title),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = title,
            authors = listOf(BookContributor("a-$author", author)),
            narrators = emptyList(),
            duration = durationMs,
            coverPath = null,
            addedAt = Timestamp(addedAt),
            updatedAt = Timestamp(addedAt),
            publishYear = year,
            series = series?.let { listOf(BookSeries(seriesId = "s-$it", seriesName = it, sequence = sequence)) }.orEmpty(),
        )

        fun sortBooksBy(
            books: List<BookListItem>,
            category: SortCategory,
            direction: SortDirection = asc,
            ignoreArticles: Boolean = false,
            listenerAverages: Map<String, ListenerAverage> = emptyMap(),
            combinedScores: Map<String, CombinedScore> = emptyMap(),
        ): List<String> =
            sortBooks(books, SortState(category, direction), ignoreArticles, listenerAverages, combinedScores)
                .map { it.title }

        context("books") {
            test("title sorts case-insensitively in both directions") {
                val books = listOf(book("banana"), book("Cherry"), book("apple"))
                sortBooksBy(books, SortCategory.TITLE, asc) shouldBe listOf("apple", "banana", "Cherry")
                sortBooksBy(books, SortCategory.TITLE, desc) shouldBe listOf("Cherry", "banana", "apple")
            }

            test("ignoring articles files The Aardvark under A; not ignoring files it under T") {
                val books = listOf(book("Mist"), book("The Aardvark"), book("Zoo"))
                sortBooksBy(books, SortCategory.TITLE, ignoreArticles = true) shouldBe
                    listOf("The Aardvark", "Mist", "Zoo")
                sortBooksBy(books, SortCategory.TITLE, ignoreArticles = false) shouldBe
                    listOf("Mist", "The Aardvark", "Zoo")
            }

            test("author breaks ties by title") {
                val books = listOf(book("B", author = "Zed"), book("Z", author = "Amy"), book("A", author = "Zed"))
                sortBooksBy(books, SortCategory.AUTHOR, asc) shouldBe listOf("Z", "A", "B")
                sortBooksBy(books, SortCategory.AUTHOR, desc) shouldBe listOf("A", "B", "Z")
            }

            test("duration sorts by length") {
                val books = listOf(book("mid", durationMs = 5), book("long", durationMs = 9), book("short", durationMs = 1))
                sortBooksBy(books, SortCategory.DURATION, asc) shouldBe listOf("short", "mid", "long")
                sortBooksBy(books, SortCategory.DURATION, desc) shouldBe listOf("long", "mid", "short")
            }

            test("year puts books with no year last in either direction") {
                val books = listOf(book("none", year = null), book("old", year = 1990), book("new", year = 2020))
                sortBooksBy(books, SortCategory.YEAR, asc) shouldBe listOf("old", "new", "none")
                sortBooksBy(books, SortCategory.YEAR, desc) shouldBe listOf("new", "old", "none")
            }

            test("added sorts by when the book arrived") {
                val books = listOf(book("b", addedAt = 20), book("a", addedAt = 10), book("c", addedAt = 30))
                sortBooksBy(books, SortCategory.ADDED, asc) shouldBe listOf("a", "b", "c")
                sortBooksBy(books, SortCategory.ADDED, desc) shouldBe listOf("c", "b", "a")
            }

            test("rating puts unrated books last in both directions") {
                val books = listOf(book("unrated"), book("low"), book("high"))
                val scores = mapOf("low" to CombinedScore(2.0, 1), "high" to CombinedScore(4.5, 1))
                sortBooksBy(books, SortCategory.RATING, asc, combinedScores = scores) shouldBe
                    listOf("low", "high", "unrated")
                sortBooksBy(books, SortCategory.RATING, desc, combinedScores = scores) shouldBe
                    listOf("high", "low", "unrated")
            }

            test("listener rating reads the listener averages, not the combined score") {
                val books = listOf(book("a"), book("b"), book("c"))
                val averages = mapOf("a" to ListenerAverage(8.0, 2), "b" to ListenerAverage(4.0, 1))
                val scores = mapOf("c" to CombinedScore(5.0, 9))
                sortBooksBy(
                    books,
                    SortCategory.LISTENER_RATING,
                    desc,
                    listenerAverages = averages,
                    combinedScores = scores,
                ) shouldBe listOf("a", "b", "c")
            }

            test("series orders by series name, then sequence, then title, with unseried books last ascending") {
                val books =
                    listOf(
                        book("loose"),
                        book("Beta 2", series = "Beta", sequence = 2.0),
                        book("Alpha 2", series = "Alpha", sequence = 2.0),
                        book("Alpha 1b", series = "Alpha", sequence = 1.0),
                        book("Alpha 1a", series = "Alpha", sequence = 1.0),
                    )
                sortBooksBy(books, SortCategory.SERIES, asc) shouldBe
                    listOf("Alpha 1a", "Alpha 1b", "Alpha 2", "Beta 2", "loose")
            }

            test("categories that do not apply to books leave the order alone") {
                val books = listOf(book("b"), book("a"))
                sortBooksBy(books, SortCategory.NAME) shouldBe listOf("b", "a")
                sortBooksBy(books, SortCategory.BOOK_COUNT) shouldBe listOf("b", "a")
            }
        }

        context("series") {
            fun seriesOf(
                name: String,
                bookCount: Int = 1,
                createdAt: Long = 0L,
            ) = SeriesWithBooks(
                series = Series(id = SeriesId(name), name = name, description = null, createdAt = Timestamp(createdAt)),
                books = List(bookCount) { book("$name-$it") },
                bookSequences = emptyMap(),
            )

            fun sortSeriesBy(
                series: List<SeriesWithBooks>,
                category: SortCategory,
                direction: SortDirection = asc,
                ignoreArticles: Boolean = false,
            ) = sortSeries(series, SortState(category, direction), ignoreArticles).map { it.series.name }

            test("name honours ignore-articles") {
                val series = listOf(seriesOf("Mist"), seriesOf("The Aardvark"), seriesOf("Zoo"))
                sortSeriesBy(series, SortCategory.NAME, ignoreArticles = true) shouldBe
                    listOf("The Aardvark", "Mist", "Zoo")
                sortSeriesBy(series, SortCategory.NAME, ignoreArticles = false) shouldBe
                    listOf("Mist", "The Aardvark", "Zoo")
            }

            test("name descending reverses") {
                val series = listOf(seriesOf("A"), seriesOf("C"), seriesOf("B"))
                sortSeriesBy(series, SortCategory.NAME, desc) shouldBe listOf("C", "B", "A")
            }

            test("book count and added sort numerically") {
                val series = listOf(seriesOf("a", 3, 20), seriesOf("b", 1, 30), seriesOf("c", 2, 10))
                sortSeriesBy(series, SortCategory.BOOK_COUNT, desc) shouldBe listOf("a", "c", "b")
                sortSeriesBy(series, SortCategory.ADDED, asc) shouldBe listOf("c", "a", "b")
            }

            test("a category that does not apply falls back to name ascending") {
                val series = listOf(seriesOf("b"), seriesOf("a"))
                sortSeriesBy(series, SortCategory.DURATION, desc) shouldBe listOf("a", "b")
            }
        }

        context("contributors") {
            fun person(
                name: String,
                bookCount: Int = 1,
            ) = ContributorWithBookCount(
                contributor = Contributor(id = ContributorId(name), name = name, description = null, imagePath = null),
                bookCount = bookCount,
            )

            fun sortPeopleBy(
                people: List<ContributorWithBookCount>,
                category: SortCategory,
                direction: SortDirection = asc,
            ) = sortContributors(people, SortState(category, direction)).map { it.contributor.name }

            test("name sorts case-insensitively") {
                val people = listOf(person("bob"), person("Carol"), person("alice"))
                sortPeopleBy(people, SortCategory.NAME, asc) shouldBe listOf("alice", "bob", "Carol")
                sortPeopleBy(people, SortCategory.NAME, desc) shouldBe listOf("Carol", "bob", "alice")
            }

            test("book count sorts numerically") {
                val people = listOf(person("a", 2), person("b", 9), person("c", 5))
                sortPeopleBy(people, SortCategory.BOOK_COUNT, desc) shouldBe listOf("b", "c", "a")
                sortPeopleBy(people, SortCategory.BOOK_COUNT, asc) shouldBe listOf("a", "c", "b")
            }

            test("a category that does not apply falls back to name ascending") {
                val people = listOf(person("b"), person("a"))
                sortPeopleBy(people, SortCategory.YEAR, desc) shouldBe listOf("a", "b")
            }
        }
    })
