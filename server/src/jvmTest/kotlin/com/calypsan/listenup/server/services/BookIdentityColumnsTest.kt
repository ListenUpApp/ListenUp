package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.match.ExternalRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The two invariants every book write passes through: the `audible` ref IS the `asin` column, and a
 * stored release date's year IS `publish_year`.
 */
class BookIdentityColumnsTest :
    FunSpec({
        val hardcover = ExternalRef("hardcover", "428")

        test("the ASIN column becomes the audible ref, beside every other catalogue's") {
            BookIdentityColumns.reconcileRefs("B08G9PRS1K", listOf(hardcover)) shouldBe
                listOf(ExternalRef("audible", "B08G9PRS1K"), hardcover)
        }

        test("an ASIN edit moves the audible ref and drops its old store") {
            BookIdentityColumns.reconcileRefs("B0NEW", listOf(ExternalRef("audible", "B0OLD", region = "uk"), hardcover)) shouldBe
                listOf(ExternalRef("audible", "B0NEW"), hardcover)
        }

        test("an unchanged ASIN keeps its store") {
            val linked = ExternalRef("audible", "B08G9PRS1K", region = "uk")
            BookIdentityColumns.reconcileRefs("B08G9PRS1K", listOf(linked)) shouldBe listOf(linked)
        }

        test("clearing the ASIN clears the audible ref and nothing else") {
            BookIdentityColumns.reconcileRefs(null, listOf(ExternalRef("audible", "B0OLD"), hardcover)) shouldBe listOf(hardcover)
            BookIdentityColumns.reconcileRefs("  ", listOf(ExternalRef("audible", "B0OLD"))) shouldBe emptyList()
        }

        test("one ref per provider, first one wins") {
            BookIdentityColumns.reconcileRefs(null, listOf(hardcover, ExternalRef("hardcover", "999"))) shouldBe listOf(hardcover)
        }

        test("a full date whose year matches is kept") {
            BookIdentityColumns.reconcileReleaseDate(2021, "2021-05-04") shouldBe "2021-05-04"
        }

        test("a date whose year disagrees with the year is cleared — the year was edited without one") {
            BookIdentityColumns.reconcileReleaseDate(2020, "2021-05-04") shouldBe null
            BookIdentityColumns.reconcileReleaseDate(null, "2021-05-04") shouldBe null
        }

        test("anything that is not a full ISO date is not a date") {
            BookIdentityColumns.reconcileReleaseDate(2021, "2021") shouldBe null
            BookIdentityColumns.reconcileReleaseDate(2021, "2021-13-01") shouldBe null
            BookIdentityColumns.reconcileReleaseDate(2021, "May 4, 2021") shouldBe null
            BookIdentityColumns.fullDateOrNull(" 2021-05-04 ") shouldBe "2021-05-04"
        }
    })
