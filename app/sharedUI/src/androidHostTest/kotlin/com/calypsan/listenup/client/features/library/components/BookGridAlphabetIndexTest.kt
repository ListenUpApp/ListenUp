package com.calypsan.listenup.client.features.library.components

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The alphabet scrollbar jumps to a letter's section header. With the inbox entry heading the grid
 * as item 0, every section sits one further on; an index that ignored it would land each jump on
 * the last book of the previous letter.
 */
class BookGridAlphabetIndexTest :
    FunSpec({
        val byTitle = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
        // Grid items: [A] Alpha, Amber, [M] Mist, [Z] Zed — headers at 0, 3 and 5.
        val gridItems =
            groupBooksWithHeaders(
                books = listOf(book("Alpha"), book("Amber"), book("Mist"), book("Zed")),
                sortState = byTitle,
                ignoreArticles = true,
            )

        test("without a header, each letter jumps to its own section header") {
            val index = bookGridAlphabetIndex(gridItems, SortCategory.TITLE, hasHeader = false).shouldNotBeNull()

            index.letters shouldBe listOf('A', 'M', 'Z')
            index.letterToIndex shouldBe mapOf('A' to 0, 'M' to 3, 'Z' to 5)
        }

        test("with the inbox entry as item 0, every letter's section sits one further on") {
            val index = bookGridAlphabetIndex(gridItems, SortCategory.TITLE, hasHeader = true).shouldNotBeNull()

            index.letterToIndex shouldBe mapOf('A' to 1, 'M' to 4, 'Z' to 6)
        }

        test("a sort with no letters has no index") {
            bookGridAlphabetIndex(gridItems, SortCategory.DURATION, hasHeader = true).shouldBeNull()
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
