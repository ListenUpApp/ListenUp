package com.calypsan.listenup.client.playback

import androidx.media3.common.C
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the start-position decision used by PlaybackService.onSetMediaItems — the #1236
 * regression pin. Voice search and Auto tap-to-play send one request item with no explicit
 * start (startIndex == C.INDEX_UNSET); the decision must resolve the book's saved resume
 * position (auto-rewind already folded in by PlaybackPreparer), never index/position zero.
 */
class AutoStartPositionTest :
    FunSpec({
        // Two files of 10 min each; book position 900_000 resolves to file 1 @ 300_000.
        val timeline =
            PlaybackTimeline.build(
                bookId = BookId("book1"),
                files =
                    listOf(
                        TimelineFileInput("af-1", "part1.mp3", "mp3", 600_000L, 1L, null, "https://s/1"),
                        TimelineFileInput("af-2", "part2.mp3", "mp3", 600_000L, 1L, null, "https://s/2"),
                    ),
            )

        test("voice-search shape (one item, no explicit start) resumes at the saved position") {
            val (index, positionMs) =
                autoStartPosition(
                    requestedStartIndex = C.INDEX_UNSET,
                    requestedStartPositionMs = C.TIME_UNSET,
                    requestItemCount = 1,
                    resumeTimeline = timeline,
                    resumePositionMs = 900_000L,
                )
            index shouldBe 1
            positionMs shouldBe 300_000L
        }

        test("explicit controller start index passes through verbatim (in-app setMediaQueue path)") {
            val (index, positionMs) =
                autoStartPosition(
                    requestedStartIndex = 3,
                    requestedStartPositionMs = 12_345L,
                    requestItemCount = 1,
                    resumeTimeline = timeline,
                    resumePositionMs = 900_000L,
                )
            index shouldBe 3
            positionMs shouldBe 12_345L
        }

        test("multi-item request falls back to Media3 defaults") {
            val (index, positionMs) =
                autoStartPosition(
                    requestedStartIndex = C.INDEX_UNSET,
                    requestedStartPositionMs = C.TIME_UNSET,
                    requestItemCount = 2,
                    resumeTimeline = timeline,
                    resumePositionMs = 900_000L,
                )
            index shouldBe C.INDEX_UNSET
            positionMs shouldBe C.TIME_UNSET
        }

        test("no resolved book falls back to Media3 defaults") {
            val (index, positionMs) =
                autoStartPosition(
                    requestedStartIndex = C.INDEX_UNSET,
                    requestedStartPositionMs = C.TIME_UNSET,
                    requestItemCount = 1,
                    resumeTimeline = null,
                    resumePositionMs = 0L,
                )
            index shouldBe C.INDEX_UNSET
            positionMs shouldBe C.TIME_UNSET
        }
    })
