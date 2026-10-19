package com.calypsan.listenup.client.features.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The phone column is built by walking [phoneDetailItems], and the Readers landing (spec §6.4) reads its index off that
 * same list, so the scroll target cannot drift from what the column draws.
 */
class BookDetailReadersLandingTest :
    FunSpec({
        test("an ordinary book: advisory, hero, actions, last match, About and rating come before Readers") {
            val items = phoneDetailItems(showServerWarning = false, isHeld = false, isPlaybackAvailable = true, hasVisibility = false)
            items shouldBe
                listOf(
                    PhoneDetailItem.ScanWarning,
                    PhoneDetailItem.Hero,
                    PhoneDetailItem.Listen,
                    PhoneDetailItem.LastMatch,
                    PhoneDetailItem.About,
                    PhoneDetailItem.Rating,
                    PhoneDetailItem.Readers,
                    PhoneDetailItem.Hardcover,
                )
            readersItemIndex(items) shouldBe 6
        }

        test("the offline banner and the admins' visibility card each push Readers one further down") {
            readersItemIndex(
                phoneDetailItems(showServerWarning = true, isHeld = false, isPlaybackAvailable = true, hasVisibility = true),
            ) shouldBe 8
        }

        test("a book that cannot play has no action unit above Readers") {
            readersItemIndex(
                phoneDetailItems(showServerWarning = false, isHeld = false, isPlaybackAvailable = false, hasVisibility = false),
            ) shouldBe 5
        }

        test("a held book is triage-only: no rating, Readers or Hardcover, so there is nowhere to land") {
            val items = phoneDetailItems(showServerWarning = false, isHeld = true, isPlaybackAvailable = true, hasVisibility = true)
            items shouldBe
                listOf(
                    PhoneDetailItem.ScanWarning,
                    PhoneDetailItem.Hero,
                    PhoneDetailItem.Held,
                    PhoneDetailItem.LastMatch,
                    PhoneDetailItem.About,
                )
            readersItemIndex(items) shouldBe null
        }

        test("every leading item has its own key, so the column's keys and its order are one list") {
            PhoneDetailItem.entries.map { it.key }.toSet().size shouldBe PhoneDetailItem.entries.size
            PhoneDetailItem.Readers.key shouldBe "readers"
        }
    })
