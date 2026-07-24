package com.calypsan.listenup.client.presentation.shelf

import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [sortShelfBooks] — the pure reducer behind the shelf-detail sort pill. The
 * incoming list is the shelf's natural added order (oldest first); the sort is display-only.
 */
class ShelfBookSortTest :
    FunSpec({
        fun book(
            id: String,
            title: String,
            author: String,
        ) = ShelfBook(id = BookId(id), title = title, authorNames = listOf(author), coverPath = null)

        // Natural order = added oldest → newest.
        val first = book("1", "Mistborn", "Brandon Sanderson")
        val second = book("2", "dune", "Frank Herbert") // lower-case title → case-insensitivity check
        val third = book("3", "The Way of Kings", "Andy Weir")
        val natural = listOf(first, second, third)

        test("ADDED_OLDEST keeps the natural added order") {
            sortShelfBooks(natural, ShelfBookSort.ADDED_OLDEST).map { it.id.value } shouldBe listOf("1", "2", "3")
        }

        test("ADDED_NEWEST reverses to newest-first") {
            sortShelfBooks(natural, ShelfBookSort.ADDED_NEWEST).map { it.id.value } shouldBe listOf("3", "2", "1")
        }

        test("TITLE sorts case-insensitively A–Z") {
            sortShelfBooks(natural, ShelfBookSort.TITLE).map { it.title } shouldBe
                listOf("dune", "Mistborn", "The Way of Kings")
        }

        test("AUTHOR sorts by first author A–Z") {
            sortShelfBooks(natural, ShelfBookSort.AUTHOR).map { it.authorNames.first() } shouldBe
                listOf("Andy Weir", "Brandon Sanderson", "Frank Herbert")
        }

        test("a book with no authors sorts last under AUTHOR without crashing") {
            val orphan = ShelfBook(id = BookId("x"), title = "Orphan", authorNames = emptyList(), coverPath = null)
            sortShelfBooks(listOf(orphan, third), ShelfBookSort.AUTHOR).map { it.id.value } shouldBe listOf("3", "x")
        }
    })
