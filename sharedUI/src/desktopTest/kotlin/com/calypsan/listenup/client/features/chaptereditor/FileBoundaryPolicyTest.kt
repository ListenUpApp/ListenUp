package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.timeline.TimeMarker
import com.calypsan.listenup.client.domain.model.AudioFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun audioFile(
    id: String,
    index: Int,
    duration: Long,
) = AudioFile(
    id = id,
    index = index,
    filename = "$id.mp3",
    format = "mp3",
    codec = "mp3",
    duration = duration,
    size = 0L,
)

class FileBoundaryPolicyTest :
    FunSpec({
        test("markers are cumulative file durations, excluding the book-end boundary") {
            val files =
                listOf(
                    audioFile("f0", index = 0, duration = 600_000L),
                    audioFile("f1", index = 1, duration = 900_000L),
                )
            val policy = FileBoundaryPolicy(audioFiles = { files })

            val markers = policy.markers()

            markers shouldBe listOf(TimeMarker(id = "file-0", timeMs = 600_000L, label = "f1.mp3", styleKey = "fileBoundary"))
        }

        test("three files produce two interior boundaries, not the book's start or end") {
            val files =
                listOf(
                    audioFile("f0", index = 0, duration = 600_000L),
                    audioFile("f1", index = 1, duration = 900_000L),
                    audioFile("f2", index = 2, duration = 300_000L),
                )
            val policy = FileBoundaryPolicy(audioFiles = { files })

            policy.markers().map { it.timeMs } shouldBe listOf(600_000L, 1_500_000L)
        }

        test("a single file produces no boundary markers") {
            val files = listOf(audioFile("f0", index = 0, duration = 600_000L))
            val policy = FileBoundaryPolicy(audioFiles = { files })

            policy.markers() shouldBe emptyList()
        }

        test("markers are ordered by file index regardless of input order") {
            val files =
                listOf(
                    audioFile("f1", index = 1, duration = 900_000L),
                    audioFile("f0", index = 0, duration = 600_000L),
                )
            val policy = FileBoundaryPolicy(audioFiles = { files })

            policy.markers().map { it.timeMs } shouldBe listOf(600_000L)
        }

        test("canDrag is always false") {
            val policy = FileBoundaryPolicy(audioFiles = { emptyList() })
            val marker = TimeMarker(id = "file-0", timeMs = 1_000L, label = null, styleKey = "fileBoundary")

            policy.canDrag(marker) shouldBe false
        }

        test("clamp returns the proposed ms unchanged") {
            val policy = FileBoundaryPolicy(audioFiles = { emptyList() })
            val marker = TimeMarker(id = "file-0", timeMs = 1_000L, label = null, styleKey = "fileBoundary")

            policy.clamp(marker, proposedMs = 5_000L, siblings = emptyList()) shouldBe 5_000L
        }

        test("onCommit is a no-op") {
            val policy = FileBoundaryPolicy(audioFiles = { emptyList() })
            val marker = TimeMarker(id = "file-0", timeMs = 1_000L, label = null, styleKey = "fileBoundary")

            // Must not throw; there is nothing to assert against since the lane is read-only.
            policy.onCommit(marker, 2_000L)
        }
    })
