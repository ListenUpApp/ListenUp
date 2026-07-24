package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize

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
    })
