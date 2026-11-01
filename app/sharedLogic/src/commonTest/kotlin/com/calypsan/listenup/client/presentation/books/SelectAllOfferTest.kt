package com.calypsan.listenup.client.presentation.books

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** "Select all N" over the books on screen, and "Deselect all" once every one is picked: one rule for every client. */
class SelectAllOfferTest :
    FunSpec({
        test("a screen with no books offers nothing") {
            selectAllOffer(onScreen = emptyList(), selected = emptySet()) shouldBe null
            selectAllOffer(onScreen = emptyList(), selected = setOf("b9")) shouldBe null
        }

        test("until every book on screen is picked, it offers to select them all, counting what is on screen") {
            selectAllOffer(onScreen = listOf("b1", "b2", "b3"), selected = emptySet()) shouldBe SelectAllOffer.SelectAll(3)
            selectAllOffer(onScreen = listOf("b1", "b2", "b3"), selected = setOf("b1")) shouldBe SelectAllOffer.SelectAll(3)
        }

        test("once every book on screen is picked, it offers to deselect them") {
            selectAllOffer(onScreen = listOf("b1", "b2"), selected = setOf("b1", "b2")) shouldBe SelectAllOffer.DeselectAll
        }

        test("picks that are off screen do not stop it offering Deselect all") {
            selectAllOffer(onScreen = listOf("b1"), selected = setOf("b1", "off-screen")) shouldBe SelectAllOffer.DeselectAll
        }
    })
