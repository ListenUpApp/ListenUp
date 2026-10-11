package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.match.EditionFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/** The candidate line on web follows the one shared narrator rule. */
class MatchWordsTest :
    FunSpec({
        test("three narrators read as a full cast") {
            candidateMetaText(candidate(narrators = listOf("A", "B", "C"))) shouldStartWith "Full cast · "
        }

        test("two narrators are both named") {
            candidateMetaText(candidate(narrators = listOf("A", "B"))) shouldStartWith "A, B · "
        }

        test("a dramatized edition that lists nobody is a full cast and does not say Dramatized as well") {
            val line = candidateMetaText(candidate(narrators = emptyList()).copy(format = EditionFormat.DRAMATIZED))
            line shouldStartWith "Full cast · "
            line shouldNotContain "Dramatized"
        }

        test("your copy lists up to two narrators and names a larger cast") {
            yourCopyMetaText(yourCopy(narrators = listOf("A", "B", "C"))) shouldBe "16h 10m · Full cast · 36 chapters"
        }
    })
