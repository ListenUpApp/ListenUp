package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.EditionFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** [narratorCredit] and [showsEditionFormat] — the one rule every platform's candidate line follows. */
class MatchFactsTest :
    FunSpec({
        data class Case(
            val narrators: List<String>,
            val format: EditionFormat?,
            val credit: NarratorCredit,
            val showsFormat: Boolean,
        )

        val cases =
            listOf(
                Case(emptyList(), EditionFormat.DRAMATIZED, NarratorCredit.FullCast, false),
                Case(emptyList(), EditionFormat.UNABRIDGED, NarratorCredit.None, true),
                Case(emptyList(), null, NarratorCredit.None, false),
                Case(listOf("A"), EditionFormat.UNABRIDGED, NarratorCredit.Names, true),
                Case(listOf("A", "B"), null, NarratorCredit.Names, false),
                Case(listOf("A", "B", "C"), EditionFormat.UNABRIDGED, NarratorCredit.FullCast, true),
                Case(listOf("A", "B", "C"), EditionFormat.DRAMATIZED, NarratorCredit.FullCast, true),
                Case(listOf("A"), EditionFormat.DRAMATIZED, NarratorCredit.Names, true),
            )

        cases.forEach { case ->
            test("${case.narrators} / ${case.format} credits ${case.credit}, edition label shown ${case.showsFormat}") {
                narratorCredit(case.narrators, case.format) shouldBe case.credit
                showsEditionFormat(case.narrators, case.format) shouldBe case.showsFormat
            }
        }
    })
