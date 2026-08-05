package com.calypsan.listenup.client.playback

import androidx.media3.common.Player
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.core.BookId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Pins the in-session auto-rewind actuator (#1220/#1237) to the TRANSPORT player and to
 * book-timeline coordinates.
 *
 * ## The regression this exists to prevent
 *
 * #1237 wired the actuator to `mediaLibrarySession?.player`, which was the raw local player at
 * the time and therefore correct. #1241 then made the session player [ChapterWindowPlayer] — a
 * chapter-scoped presentation wrapper — without revisiting the actuator, so from that commit on
 * every resume-after-a-pause handed BOOK coordinates to a player that reads them as CHAPTER
 * coordinates and clamps them to the current chapter. Instead of stepping back five seconds,
 * playback jumped to the next chapter boundary. `ChapterWindowPlayerSeekTest` demonstrates that
 * reinterpretation directly.
 *
 * [AutoRewindSeeker] closes the hole by construction rather than by convention: it holds a
 * [PlaybackTransport], whose only player accessor is
 * [PlaybackTransport.activeTransportPlayer]. There is no session player in reach to get wrong.
 *
 * The numbers below are the ones from the production incident (2026-08-05): position
 * 2_980_000 ms inside a chapter running 2_782_000..3_155_000 ms, resumed after a ~56 minute
 * pause, which earns the 5 s rung of [autoRewindMs].
 */
class AutoRewindSeekTest :
    FunSpec({

        // A single-file m4b — the shape every book in the reporting library takes, and the shape
        // that makes the coordinate confusion maximally wrong (positionInFileMs IS the book
        // position, so it always overshoots a chapter window and clamps to its far edge).
        val singleFileTimeline =
            PlaybackTimeline.build(
                bookId = BookId("zen-mind"),
                files =
                    listOf(
                        TimelineFileInput("af-1", "book.m4b", "m4b", 5_000_000L, 1L, null, "https://s/1"),
                    ),
            )

        val twoFileTimeline =
            PlaybackTimeline.build(
                bookId = BookId("two-parter"),
                files =
                    listOf(
                        TimelineFileInput("af-1", "part1.mp3", "mp3", 600_000L, 1L, null, "https://s/1"),
                        TimelineFileInput("af-2", "part2.mp3", "mp3", 600_000L, 1L, null, "https://s/2"),
                    ),
            )

        test("seeks the transport player, never the session player") {
            val player = FakeExoPlayer()
            val transport = FakeTransport(player = player, bookPositionMs = 2_980_000L)
            val seeker = AutoRewindSeeker(transport) { singleFileTimeline }

            seeker.seekBack(5_000L)

            withClue("the actuator must resolve its player through the transport seam") {
                transport.activeTransportPlayerCalls shouldBe 1
            }
            player.seekCalls.size shouldBe 1
        }

        test("seeks in book-timeline coordinates, not chapter-relative ones") {
            val player = FakeExoPlayer()
            val transport = FakeTransport(player = player, bookPositionMs = 2_980_000L)
            val seeker = AutoRewindSeeker(transport) { singleFileTimeline }

            val applied = seeker.seekBack(5_000L)

            // 2_980_000 - 5_000. NOT 3_155_000, which is where the chapter-window wrapper
            // would have clamped this same request (see ChapterWindowPlayerSeekTest).
            player.seekCalls shouldBe listOf(0 to 2_975_000L)
            applied shouldBe 2_975_000L
        }

        test("resolves the rewound position across a multi-file book") {
            val player = FakeExoPlayer()
            val transport = FakeTransport(player = player, bookPositionMs = 905_000L)
            val seeker = AutoRewindSeeker(transport) { twoFileTimeline }

            val applied = seeker.seekBack(5_000L)

            // 900_000 book-relative is 300_000 into the second file.
            player.seekCalls shouldBe listOf(1 to 300_000L)
            applied shouldBe 900_000L
        }

        test("clamps at the start of the book rather than seeking negative") {
            val player = FakeExoPlayer()
            val transport = FakeTransport(player = player, bookPositionMs = 2_000L)
            val seeker = AutoRewindSeeker(transport) { singleFileTimeline }

            val applied = seeker.seekBack(30_000L)

            player.seekCalls shouldBe listOf(0 to 0L)
            applied shouldBe 0L
        }

        test("falls back to a file-relative seek when no timeline is prepared") {
            val player = FakeExoPlayer(stubbedPosition = 40_000L)
            val transport = FakeTransport(player = player, bookPositionMs = 40_000L)
            val seeker = AutoRewindSeeker(transport) { null }

            seeker.seekBack(5_000L)

            // The single-argument overload — file-relative — recorded separately from the
            // (index, position) calls precisely so this test can tell the two apart.
            player.fileRelativeSeekCalls shouldBe listOf(35_000L)
            player.seekCalls shouldBe emptyList()
        }

        test("does nothing when no transport player is available") {
            val transport = FakeTransport(player = null, bookPositionMs = 2_980_000L)
            val seeker = AutoRewindSeeker(transport) { singleFileTimeline }

            seeker.seekBack(5_000L).shouldBeNull()
        }
    })

/**
 * [PlaybackTransport] fake that counts transport-player lookups.
 *
 * It exposes no session player at all — the point of routing the actuator through this seam is
 * that the wrong player is not reachable, so the fake models exactly that.
 */
private class FakeTransport(
    private val player: Player?,
    private val bookPositionMs: Long,
) : PlaybackTransport {
    var activeTransportPlayerCalls = 0
        private set

    override fun activeTransportPlayer(): Player? {
        activeTransportPlayerCalls++
        return player
    }

    override fun bookRelativePositionMs(): Long = bookPositionMs

    override fun applyResumeSpeed(speed: Float) = Unit
}
