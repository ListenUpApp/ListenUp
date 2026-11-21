package com.calypsan.listenup.api.dto.activity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val MINUTE = 60_000L

private fun span(
    startMin: Long,
    endMin: Long,
    userId: String = "kyle",
    bookId: String = "red-rising",
) = ListenSpan(userId = userId, bookId = bookId, startedAtMs = startMin * MINUTE, endedAtMs = endMin * MINUTE)

/** The one rule for what a sitting is, which the Activity feed and Discover both read. */
class SittingsTest :
    FunSpec({
        test("spans with a short pause between them are one sitting, their lengths added up") {
            val sittings = listOf(span(0, 10), span(15, 40)).groupIntoSittings { it }

            sittings shouldHaveSize 1
            sittings.single().listenedMs shouldBe 35 * MINUTE
            sittings.single().startedAtMs shouldBe 0L
            sittings.single().endedAtMs shouldBe 40 * MINUTE
        }

        test("a pause of exactly the gap still joins; one minute more starts a new sitting") {
            val gap = RealListen.SITTING_GAP_MS / MINUTE

            listOf(span(0, 10), span(10 + gap, 20 + gap)).groupIntoSittings { it } shouldHaveSize 1
            listOf(span(0, 10), span(11 + gap, 20 + gap)).groupIntoSittings { it } shouldHaveSize 2
        }

        test("someone else listening in between never splits a sitting") {
            // The feed's old adjacent-rows merge split Kyle's evening in two whenever anyone else's row landed between.
            val spans = listOf(span(20, 21), span(10, 15, userId = "ada"), span(0, 10))

            val kyle = spans.groupIntoSittings { it }.filter { it.userId == "kyle" }

            kyle shouldHaveSize 1
            kyle.single().listenedMs shouldBe 11 * MINUTE
        }

        test("different books are different sittings, even back to back") {
            listOf(span(0, 10), span(10, 20, bookId = "golden-son")).groupIntoSittings { it } shouldHaveSize 2
        }

        test("overlapping spans from two devices join one sitting, and their shared time counts once") {
            val sittings = listOf(span(0, 30), span(20, 40)).groupIntoSittings { it }

            sittings shouldHaveSize 1
            sittings.single().listenedMs shouldBe 40 * MINUTE
        }

        test("two devices playing the same hour at once are one hour of listening") {
            listOf(span(0, 60), span(0, 60)).groupIntoSittings { it }.single().listenedMs shouldBe 60 * MINUTE
        }

        test("a span inside another adds nothing to it") {
            listOf(span(0, 60), span(10, 20)).groupIntoSittings { it }.single().listenedMs shouldBe 60 * MINUTE
        }

        test("a span that ends before it starts adds nothing") {
            val inverted = span(30, 20)

            listOf(span(0, 10), inverted).groupIntoSittings { it }.single().listenedMs shouldBe 10 * MINUTE
        }

        test("whether a sitting is real is judged on its union, not its spans added up") {
            // Forty seconds heard on two devices at once is forty seconds, not eighty.
            val phone = span(0, 0).copy(endedAtMs = 40_000L)
            val tablet = span(0, 0).copy(endedAtMs = 40_000L)

            listOf(phone, tablet).groupIntoSittings { it }.single().isRealListen shouldBe false
        }

        test("sittings come newest first, and their parts oldest first") {
            val older = span(0, 5, bookId = "golden-son")
            val first = span(100, 110)
            val second = span(115, 120)

            val sittings = listOf(second, older, first).groupIntoSittings { it }

            sittings.map { it.bookId } shouldBe listOf("red-rising", "golden-son")
            sittings.first().parts shouldBe listOf(first, second)
        }

        test("fragments under a minute that add up to one are a real listen; a lone tap is not") {
            val fragments = listOf(span(0, 0).copy(endedAtMs = 40_000L), span(5, 5).copy(endedAtMs = 5 * MINUTE + 30_000L))

            fragments.groupIntoSittings { it }.single().isRealListen shouldBe true
            listOf(span(0, 0).copy(endedAtMs = 8_000L)).groupIntoSittings { it }.single().isRealListen shouldBe false
        }

        test("exactly the threshold is a real listen") {
            listOf(span(0, 1)).groupIntoSittings { it }.single().isRealListen shouldBe true
        }

        test("nothing in, nothing out") {
            emptyList<ListenSpan>().groupIntoSittings { it }.shouldBeEmpty()
        }
    })
