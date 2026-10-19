package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.readers.Reader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun reader(
    id: String,
    you: Boolean = false,
    pct: Int? = null,
    finishes: List<Long> = emptyList(),
) = Reader(userId = id, displayName = id.replaceFirstChar { it.uppercase() }, isYou = you, currentProgressPct = pct, finishes = finishes)

class ReadersSummaryTest :
    FunSpec({
        test("you are never in it") {
            val summary = BookReaders(listOf(reader("me", you = true, pct = 40), reader("maya", pct = 10))).summary()
            summary.listening.map { it.userId } shouldBe listOf("maya")
            summary.avatars.map { it.userId } shouldBe listOf("maya")
        }

        test("listening comes before finished, and someone listening again is listening, not finished") {
            val summary =
                BookReaders(
                    listOf(reader("tom", finishes = listOf(5L)), reader("maya", pct = 30, finishes = listOf(2L))),
                ).summary()
            summary.listening.map { it.userId } shouldBe listOf("maya")
            summary.finished.map { it.userId } shouldBe listOf("tom")
            summary.avatars.map { it.userId } shouldBe listOf("maya", "tom")
        }

        test("at most three avatars; the sentence names two a clause and counts the rest") {
            val summary =
                BookReaders(
                    listOf("a", "b", "c", "d", "e").map { reader(it, pct = 10) } + reader("f", finishes = listOf(1L)),
                ).summary()
            summary.avatars.map { it.userId } shouldBe listOf("a", "b", "c")
            summary.othersCount shouldBe 3
        }

        test("only you: empty, so the line hides") {
            BookReaders(listOf(reader("me", you = true, pct = 40))).summary() shouldBe
                ReadersSummary(listening = emptyList(), finished = emptyList(), avatars = emptyList(), othersCount = 0)
        }

        test("a reader with neither progress nor a finish is in neither clause") {
            BookReaders(listOf(reader("idle"))).summary().avatars shouldBe emptyList()
        }
    
        test("the sentence's names are first names, as the boards write them") {
            ReaderRef("p", "Priya Nair").firstName shouldBe "Priya"
            ReaderRef("m", "Maya").firstName shouldBe "Maya"
            ReaderRef("j", "  Jean-Luc   Picard ").firstName shouldBe "Jean-Luc"
            ReaderRef("x", "").firstName shouldBe ""
        }
    })
