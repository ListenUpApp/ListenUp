package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.timeline.TimeMarker
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun chapter(
    id: String,
    startTime: Long,
) = Chapter(id = id, title = id, duration = 0L, startTime = startTime)

private fun marker(
    id: String,
    ms: Long,
) = TimeMarker(id = id, timeMs = ms, label = null, styleKey = "chapterBoundary")

class ChapterBoundaryPolicyTest :
    FunSpec({
        val chapters =
            listOf(
                chapter("c0", 0L),
                chapter("c1", 100_000L),
                chapter("c2", 200_000L),
            )

        test("the first chapter cannot be dragged") {
            val policy = ChapterBoundaryPolicy(chapters = { chapters }, onRetime = { _, _ -> })

            policy.canDrag(marker("c0", 0L)) shouldBe false
        }

        test("a non-first chapter can be dragged") {
            val policy = ChapterBoundaryPolicy(chapters = { chapters }, onRetime = { _, _ -> })

            policy.canDrag(marker("c1", 100_000L)) shouldBe true
            policy.canDrag(marker("c2", 200_000L)) shouldBe true
        }

        test("clamp keeps a drag strictly between its neighbors, respecting the minimum duration floor") {
            val policy = ChapterBoundaryPolicy(chapters = { chapters }, onRetime = { _, _ -> })
            val dragged = marker("c1", 100_000L)
            val siblings = listOf(marker("c0", 0L), marker("c2", 200_000L))

            // Below the previous sibling's floor (0 + 50s) clamps up to the floor.
            policy.clamp(dragged, proposedMs = 10_000L, siblings = siblings) shouldBe 50_000L

            // Above the next sibling's ceiling (200s - 50s) clamps down to the ceiling.
            policy.clamp(dragged, proposedMs = 190_000L, siblings = siblings) shouldBe 150_000L

            // Comfortably between the two floors/ceilings passes through unchanged.
            policy.clamp(dragged, proposedMs = 100_000L, siblings = siblings) shouldBe 100_000L
        }

        test("clamp falls back to 0 when there is no earlier sibling") {
            val policy = ChapterBoundaryPolicy(chapters = { chapters }, onRetime = { _, _ -> })
            val dragged = marker("solo", 100_000L)
            val siblings = listOf(marker("c2", 200_000L))

            policy.clamp(dragged, proposedMs = -5_000L, siblings = siblings) shouldBe 0L
        }

        test("clamp has no upper bound when there is no later sibling") {
            val policy = ChapterBoundaryPolicy(chapters = { chapters }, onRetime = { _, _ -> })
            val dragged = marker("solo", 100_000L)
            val siblings = listOf(marker("c0", 0L))

            policy.clamp(dragged, proposedMs = 10_000_000L, siblings = siblings) shouldBe 10_000_000L
        }

        test("onCommit invokes onRetime with the marker id and the caller-supplied ms") {
            val retimed = mutableListOf<Pair<String, Long>>()
            val policy =
                ChapterBoundaryPolicy(
                    chapters = { chapters },
                    onRetime = { chapterId, newMs -> retimed += chapterId to newMs },
                )

            policy.onCommit(marker("c1", 100_000L), 123_456L)

            retimed shouldBe listOf("c1" to 123_456L)
        }
    })
