package com.calypsan.listenup.client.domain.readers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReaderLineTest :
    FunSpec({

        test("flattenToLines: reading lines first, then finishes newest-first across readers") {
            val readers =
                listOf(
                    Reader("u2", "Jake", isYou = false, currentProgressPct = 43, finishes = emptyList()),
                    Reader("me", "You", isYou = true, currentProgressPct = null, finishes = listOf(1_777_000_000_000L, 1_610_000_000_000L)),
                    Reader("u3", "Mary", isYou = false, currentProgressPct = null, finishes = listOf(1_600_000_000_000L)),
                )
            flattenToLines(readers).map { it.kind } shouldBe
                listOf(
                    ReaderLineKind.Reading(43),
                    ReaderLineKind.Finished(1_777_000_000_000L),
                    ReaderLineKind.Finished(1_610_000_000_000L),
                    ReaderLineKind.Finished(1_600_000_000_000L),
                )
        }

        test("a reader who is both reading now and has past finishes yields a reading line AND finished lines") {
            val readers = listOf(Reader("me", "You", true, currentProgressPct = 10, finishes = listOf(500L, 200L)))
            flattenToLines(readers).map { it.kind } shouldBe
                listOf(
                    ReaderLineKind.Reading(10),
                    ReaderLineKind.Finished(500L),
                    ReaderLineKind.Finished(200L),
                )
        }

        test("flattenToLines: empty readers yields empty lines") {
            flattenToLines(emptyList()) shouldBe emptyList()
        }

        test("flattenToLines: reader ids and names are preserved in lines") {
            val readers =
                listOf(
                    Reader("u1", "Alice", isYou = false, currentProgressPct = 75, finishes = emptyList()),
                )
            val lines = flattenToLines(readers)
            lines.size shouldBe 1
            lines[0].userId shouldBe "u1"
            lines[0].name shouldBe "Alice"
            lines[0].isYou shouldBe false
        }
    })
