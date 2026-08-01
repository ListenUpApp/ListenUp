package com.calypsan.listenup.client.presentation.contributoredit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for [normalizeContributorName] — the punctuation/spacing-insensitive
 * comparison used to detect a rename collision with an existing contributor.
 */
class ContributorNameNormalizationTest :
    FunSpec({

        test("punctuation and spacing variants of the same name normalize equal") {
            val a = normalizeContributorName("George R.R. Martin")
            val b = normalizeContributorName("George R. R. Martin")
            val c = normalizeContributorName("GEORGE  R.R.  MARTIN")

            a shouldBe b
            b shouldBe c
        }

        test("James S.A. Corey variants normalize equal") {
            normalizeContributorName("James S.A. Corey") shouldBe
                normalizeContributorName("James S. A. Corey")
        }

        test("case alone does not prevent equality") {
            normalizeContributorName("Stephen King") shouldBe normalizeContributorName("STEPHEN KING")
        }

        test("collapses runs of internal whitespace") {
            normalizeContributorName("Stephen   King") shouldBe normalizeContributorName("Stephen King")
        }

        test("trims leading and trailing whitespace") {
            normalizeContributorName("  Stephen King  ") shouldBe normalizeContributorName("Stephen King")
        }

        test("genuinely different names do NOT normalize equal") {
            normalizeContributorName("George Martin") shouldNotBe normalizeContributorName("George R.R. Martin")
        }

        test("different people entirely do NOT normalize equal") {
            normalizeContributorName("Stephen King") shouldNotBe normalizeContributorName("Richard Bachman")
        }
    })
