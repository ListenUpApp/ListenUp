package com.calypsan.listenup.client.playback

import androidx.media3.common.Player
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.core.BookId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Demonstrates, against the real [ChapterWindowPlayer], why book coordinates must never be sent
 * to the session player — the production defect behind [AutoRewindSeeker].
 *
 * ## What this reproduces
 *
 * On 2026-08-05 a listener resumed a single-file audiobook after a ~56 minute pause at book
 * position 2_980_000 ms, inside a chapter running 2_782_000..3_155_000 ms. The 5 s rung of
 * [autoRewindMs] applied, so the actuator asked for 2_975_000 ms — and playback jumped forward to
 * the start of the *next* chapter. The recorded listening span shows exactly that: +177 s of
 * content in 2 s of wall clock, landing on the chapter boundary.
 *
 * The cause is not a bug in this class — the reinterpretation below is precisely its job, and is
 * correct for the system surfaces (Auto, notification, lock screen) it presents to. The bug was
 * aiming a book-coordinate seek at it. This test pins the hazard so the reinterpretation is
 * visible in a test rather than only in a KDoc paragraph, and so anyone tempted to route a
 * book-relative seek through the session player meets it here first.
 *
 * ## Note on testability
 *
 * [ChapterWindowPlayer]'s KDoc long claimed it could not be constructed in this source set for
 * want of a real `Looper`. That is not true under Robolectric, which this lane has: `FakeExoPlayer`
 * returns `Looper.getMainLooper()`, and `SimpleBasePlayer`'s constructor is satisfied. The claim is
 * corrected in that KDoc.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterWindowPlayerSeekTest {
    private val chapterStartMs = 2_782_000L
    private val nextChapterStartMs = 3_155_000L
    private val bookPositionMs = 2_980_000L

    /** Single-file m4b — the shape that makes the coordinate confusion maximally wrong. */
    private val timeline =
        PlaybackTimeline.build(
            bookId = BookId("zen-mind"),
            files = listOf(TimelineFileInput("af-1", "book.m4b", "m4b", 5_000_000L, 1L, null, "https://s/1")),
        )

    private val chapters =
        listOf(
            Chapter(id = "ch-8", title = "Bowing", duration = 488_000L, startTime = 2_294_000L),
            Chapter(id = "ch-9", title = "Nothing Special", duration = 373_000L, startTime = chapterStartMs),
            Chapter(id = "ch-10", title = "Part 2: Right Attitude", duration = 156_000L, startTime = nextChapterStartMs),
        )

    private fun wrapperOver(fake: FakeExoPlayer) =
        ChapterWindowPlayer(
            player = fake,
            chaptersProvider = { chapters },
            timelineProvider = { timeline },
        )

    private fun seekableFake() =
        FakeExoPlayer(
            stubbedPosition = bookPositionMs,
            stubbedMediaItemIndex = 0,
            availableCommands =
                Player.Commands
                    .Builder()
                    .addAll(Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .build(),
        )

    @Test
    fun `a book-coordinate seek aimed at the session wrapper lands on the next chapter boundary`() {
        val fake = seekableFake()
        val wrapper = wrapperOver(fake)

        // Exactly what the pre-fix actuator did: resolve the rewound BOOK position through the
        // timeline, then seek the session player with it.
        val rewound = timeline.resolve(bookPositionMs - 5_000L)
        wrapper.seekTo(rewound.mediaItemIndex, rewound.positionInFileMs)

        // The wrapper read 2_975_000 as a position WITHIN the current chapter, clamped it to the
        // chapter's 373 s length, and turned a 5-second step back into a jump to the next chapter.
        assertEquals(
            "book-coordinate seek through the chapter wrapper must be shown clamping to the window end",
            listOf(0 to nextChapterStartMs),
            fake.seekCalls,
        )
    }

    @Test
    fun `the same rewind aimed at the transport player lands five seconds back`() {
        val fake = seekableFake()
        val transport = TransportOver(fake, bookPositionMs)

        AutoRewindSeeker(transport) { timeline }.seekBack(5_000L)

        assertEquals(listOf(0 to bookPositionMs - 5_000L), fake.seekCalls)
    }

    @Test
    fun `a chapter-relative seek through the wrapper is still interpreted chapter-relatively`() {
        val fake = seekableFake()
        val wrapper = wrapperOver(fake)

        // 60 s into the current chapter — the coordinate space a system surface's seek bar speaks.
        wrapper.seekTo(0, 60_000L)

        assertEquals(listOf(0 to chapterStartMs + 60_000L), fake.seekCalls)
    }
}

/** Minimal [PlaybackTransport] over a fake, for the contrast case above. */
private class TransportOver(
    private val player: Player,
    private val bookPositionMs: Long,
) : PlaybackTransport {
    override fun activeTransportPlayer(): Player = player

    override fun bookRelativePositionMs(): Long = bookPositionMs

    override fun applyResumeSpeed(speed: Float) = Unit
}
