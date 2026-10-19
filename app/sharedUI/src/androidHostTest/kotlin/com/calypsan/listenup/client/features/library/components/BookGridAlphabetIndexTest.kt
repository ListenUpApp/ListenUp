package com.calypsan.listenup.client.features.library.components

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The Library's headers and alphabet scrollbar read the shared [LetterSection]s. The scrollbar jumps
 * to a letter's header; with the inbox entry heading the grid as item 0, every section sits one
 * further on, and an index that ignored it would land each jump on the last book of the previous
 * letter.
 */
class BookGridAlphabetIndexTest :
    FunSpec({
        val books = listOf(book("Alpha"), book("Amber"), book("Mist"), book("Zed"))
        val letters = listOf(LetterSection("A", 0, 2), LetterSection("M", 2, 1), LetterSection("Z", 3, 1))

        test("a header goes before each section") {
            bookGridItems(books, letters).map {
                (it as? BookGridItem.Header)?.letter ?: (it as BookGridItem.BookItem).book.title
            } shouldBe listOf('A', "Alpha", "Amber", 'M', "Mist", 'Z', "Zed")
        }

        test("a recurring letter gets a header per run, each keyed apart") {
            val recurring = listOf(LetterSection("#", 0, 1), LetterSection("B", 1, 1), LetterSection("#", 2, 1))
            val headers = bookGridItems(books.take(3), recurring).filterIsInstance<BookGridItem.Header>()
            headers.map { it.letter } shouldBe listOf('#', 'B', '#')
            headers.map { it.key }.distinct().size shouldBe 3
        }

        test("the index points at each header, shifted past a leading inbox row") {
            libraryAlphabetIndex(letters, withHeaders = true, leadingItems = 0).shouldNotBeNull().letterToIndex shouldBe
                mapOf('A' to 0, 'M' to 3, 'Z' to 5)
            libraryAlphabetIndex(letters, withHeaders = true, leadingItems = 1).shouldNotBeNull().letterToIndex shouldBe
                mapOf('A' to 1, 'M' to 4, 'Z' to 6)
        }

        test("a list without headers (authors, series) points at each section's first row") {
            libraryAlphabetIndex(letters, withHeaders = false, leadingItems = 0).shouldNotBeNull().letterToIndex shouldBe
                mapOf('A' to 0, 'M' to 2, 'Z' to 3)
        }

        test("a recurring letter jumps to its first run, and the rail keeps list order") {
            val recurring = listOf(LetterSection("#", 0, 1), LetterSection("B", 1, 1), LetterSection("#", 2, 1))
            val index = libraryAlphabetIndex(recurring, withHeaders = false, leadingItems = 0).shouldNotBeNull()
            index.letters shouldBe listOf('#', 'B')
            index.letterToIndex shouldBe mapOf('#' to 0, 'B' to 1)
        }

        test("a descending sort runs the rail Z to A") {
            val descending = listOf(LetterSection("Z", 0, 1), LetterSection("M", 1, 1), LetterSection("A", 2, 2))
            libraryAlphabetIndex(descending, withHeaders = true, leadingItems = 0).shouldNotBeNull().letters shouldBe
                listOf('Z', 'M', 'A')
        }

        test("no letters: no headers and no index") {
            bookGridItems(books, emptyList()).all { it is BookGridItem.BookItem } shouldBe true
            libraryAlphabetIndex(emptyList(), withHeaders = true, leadingItems = 0).shouldBeNull()
        }
    })

private fun book(title: String) =
    BookListItem(
        id = BookId(title.lowercase()),
        libraryId = LibraryId("lib"),
        folderId = FolderId("folder"),
        title = title,
        authors = emptyList(),
        narrators = emptyList(),
        duration = 3_600_000L,
        coverPath = null,
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )
