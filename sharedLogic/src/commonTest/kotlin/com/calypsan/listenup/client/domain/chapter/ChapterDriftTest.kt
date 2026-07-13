package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan

private fun chapters(
    vararg starts: Long,
    bookEnd: Long,
): List<Chapter> =
    starts.toList().mapIndexed { i, s ->
        val end = if (i == starts.lastIndex) bookEnd else starts[i + 1]
        Chapter(id = "c$i", title = "C$i", duration = end - s, startTime = s)
    }

class ChapterDriftTest :
    FunSpec({
        val book = 1_000_000L

        test("single anchor applies a constant shift") {
            val cs = chapters(0, 100_000, 500_000, bookEnd = book)
            val result = correctDrift(cs, listOf(ChapterAnchor("c1", trueStartMs = 110_000)), book)
            result as DriftResult.Corrected
            result.chapters.map { it.startTime } shouldBe listOf(10_000L, 110_000L, 510_000L)
        }

        test("two anchors interpolate growing drift (affine)") {
            val cs = chapters(0, 250_000, 500_000, 750_000, bookEnd = book)
            val result = correctDrift(cs, listOf(ChapterAnchor("c0", 0), ChapterAnchor("c3", 790_000)), book)
            result as DriftResult.Corrected
            val starts = result.chapters.map { it.startTime }
            starts.first() shouldBe 0L
            starts.last() shouldBe 790_000L
            starts[1] shouldBe 263_333L
            starts[2] shouldBe 526_667L
        }

        test("output stays contiguous: each duration bridges to the next start, last to book end") {
            val cs = chapters(0, 250_000, 500_000, bookEnd = book)
            val result = correctDrift(cs, listOf(ChapterAnchor("c0", 5_000), ChapterAnchor("c2", 520_000)), book)
            result as DriftResult.Corrected
            val ch = result.chapters
            for (i in 0 until ch.lastIndex) {
                (ch[i].startTime + ch[i].duration) shouldBe ch[i + 1].startTime
            }
            (ch.last().startTime + ch.last().duration) shouldBe book
        }

        test("inverted anchors (would reverse order) are rejected") {
            val cs = chapters(0, 250_000, 500_000, bookEnd = book)
            val result = correctDrift(cs, listOf(ChapterAnchor("c0", 600_000), ChapterAnchor("c2", 100_000)), book)
            result shouldBe DriftResult.Rejected.InvertedAnchors
        }

        test("locked chapters keep their start") {
            val cs = chapters(0, 250_000, 500_000, bookEnd = book)
            val result = correctDrift(cs, listOf(ChapterAnchor("c0", 0), ChapterAnchor("c2", 520_000)), book, lockedIds = setOf("c1"))
            result as DriftResult.Corrected
            result.chapters.first { it.id == "c1" }.startTime shouldBe 250_000L
        }

        test("scales to a 300-chapter / 65h book") {
            val total = 65L * 60 * 60 * 1000
            val starts = (0 until 300).map { it * (total / 300) }.toLongArray()
            val cs = chapters(*starts, bookEnd = total)
            val result = correctDrift(cs, listOf(ChapterAnchor("c0", 3_000), ChapterAnchor("c299", starts.last() + 48_000)), total)
            result as DriftResult.Corrected
            result.chapters shouldHaveSize 300
            result.chapters.first().startTime shouldBe 3_000L
        }

        test("three anchors: flat early segment then a ramp, order and contiguity preserved") {
            // c0..c5 evenly spaced across a 6-chapter, 1_200_000ms book.
            val cs = chapters(0, 200_000, 400_000, 600_000, 800_000, 1_000_000, bookEnd = 1_200_000L)
            // Anchor c0 and c2 to the SAME offset (flat/no-drift early on), then c5 drifts +30s late.
            val anchors =
                listOf(
                    ChapterAnchor("c0", 0L),
                    ChapterAnchor("c2", 400_000L), // zero correction through here
                    ChapterAnchor("c5", 1_030_000L), // +30s by the end
                )
            val result = correctDrift(cs, anchors, bookDurationMs = 1_200_000L)
            result as DriftResult.Corrected
            val starts = result.chapters.map { it.startTime }
            starts[0] shouldBe 0L
            starts[2] shouldBe 400_000L // untouched — anchors agree, zero slope in this segment
            starts[5] shouldBe 1_030_000L // exact pin
            starts[3] shouldBeGreaterThan 600_000L // ramp segment: c3 shifted later than its raw start
            // contiguity still holds end-to-end
            for (i in 0 until result.chapters.lastIndex) {
                (result.chapters[i].startTime + result.chapters[i].duration) shouldBe result.chapters[i + 1].startTime
            }
        }

        test("N anchors (five) preserve order and contiguity on a larger set") {
            val total = 65L * 60 * 60 * 1000
            val starts = (0 until 300).map { it * (total / 300) }.toLongArray()
            val cs = chapters(*starts, bookEnd = total)
            val anchors =
                listOf(
                    ChapterAnchor("c0", 3_000L),
                    ChapterAnchor("c60", starts[60] + 8_000L),
                    ChapterAnchor("c150", starts[150] + 15_000L),
                    ChapterAnchor("c220", starts[220] + 20_000L),
                    ChapterAnchor("c299", starts[299] + 48_000L),
                )
            val result = correctDrift(cs, anchors, total)
            result as DriftResult.Corrected
            result.chapters shouldHaveSize 300
            result.chapters.map { it.startTime } shouldBe result.chapters.map { it.startTime }.sorted()
            for (i in 0 until result.chapters.lastIndex) {
                (result.chapters[i].startTime + result.chapters[i].duration) shouldBe result.chapters[i + 1].startTime
            }
        }

        test("unknown chapter id in an anchor is still rejected as BadAnchors with 3+ anchors") {
            val cs = chapters(0, 250_000, 500_000, bookEnd = 1_000_000L)
            val result =
                correctDrift(
                    cs,
                    listOf(ChapterAnchor("c0", 0L), ChapterAnchor("nope", 10_000L), ChapterAnchor("c2", 520_000L)),
                    1_000_000L,
                )
            result shouldBe DriftResult.Rejected.BadAnchors
        }

        test("an inverted segment among 3+ anchors is still rejected as InvertedAnchors") {
            val cs = chapters(0, 250_000, 500_000, 750_000, bookEnd = 1_000_000L)
            val result =
                correctDrift(
                    cs,
                    listOf(ChapterAnchor("c0", 0L), ChapterAnchor("c1", 600_000L), ChapterAnchor("c3", 100_000L)),
                    1_000_000L,
                )
            result shouldBe DriftResult.Rejected.InvertedAnchors
        }
    })
