package com.calypsan.listenup.web.features.shelf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Where a dragged book lands.
 *
 * Worth its own spec rather than being proved through the DOM: the off-by-one in a downward drag
 * is invisible in a screenshot and obvious in a list.
 */
class ShelfOrderTest :
    FunSpec({

        val books = listOf("a", "b", "c", "d")

        test("dragging upward puts the book where the pointer is") {
            reorderedBy(books, from = 2, to = 0) shouldBe listOf("c", "a", "b", "d")
        }

        test("dragging downward lands on the target, not one short of it") {
            // The whole reason this is remove-then-insert. Lifting "a" shifts everything up by one,
            // so inserting at the raw target would count that shift twice and land at index 1.
            reorderedBy(books, from = 0, to = 2) shouldBe listOf("b", "c", "a", "d")
        }

        test("dragging to the end puts the book last") {
            reorderedBy(books, from = 0, to = 3) shouldBe listOf("b", "c", "d", "a")
        }

        test("a drag that goes nowhere changes nothing") {
            reorderedBy(books, from = 1, to = 1) shouldBe books
        }

        test("a drag that ends off the list is not an error") {
            // A pointer can leave the list, and a row can vanish mid-drag. Neither is worth a crash.
            reorderedBy(books, from = 0, to = 9) shouldBe books
            reorderedBy(books, from = -1, to = 2) shouldBe books
            reorderedBy(emptyList<String>(), from = 0, to = 0) shouldBe emptyList()
        }

        test("neighbours swap cleanly in both directions") {
            reorderedBy(books, from = 1, to = 2) shouldBe listOf("a", "c", "b", "d")
            reorderedBy(books, from = 2, to = 1) shouldBe listOf("a", "c", "b", "d")
        }
    })
