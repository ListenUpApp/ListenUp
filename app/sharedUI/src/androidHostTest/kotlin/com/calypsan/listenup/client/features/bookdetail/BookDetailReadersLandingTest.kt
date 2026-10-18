package com.calypsan.listenup.client.features.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Where the phone column's Readers item sits, so Home's finishers line can land on it (spec §6.4): the items above it
 * are the offline banner (when shown), the scan warning, the hero, the action unit (when there is one), the last-match
 * row, About, the admins' visibility card (when shown) and the rating.
 */
class BookDetailReadersLandingTest :
    FunSpec({
        test("an ordinary book: scan warning, hero, actions, last match, About and rating come first") {
            readersItemIndex(
                showServerWarning = false,
                hasActionUnit = true,
                hasVisibility = false,
                isHeld = false,
            ) shouldBe 6
        }

        test("the offline banner and the admins' visibility card each push Readers one further down") {
            readersItemIndex(
                showServerWarning = true,
                hasActionUnit = true,
                hasVisibility = true,
                isHeld = false,
            ) shouldBe 8
        }

        test("a book that cannot play has no action unit above Readers") {
            readersItemIndex(
                showServerWarning = false,
                hasActionUnit = false,
                hasVisibility = false,
                isHeld = false,
            ) shouldBe 5
        }

        test("a held book shows no Readers, so there is nowhere to land") {
            readersItemIndex(
                showServerWarning = false,
                hasActionUnit = true,
                hasVisibility = false,
                isHeld = true,
            ) shouldBe null
        }
    })
