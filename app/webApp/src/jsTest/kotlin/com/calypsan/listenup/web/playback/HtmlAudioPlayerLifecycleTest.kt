package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlaybackState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.url.URL

private const val FIRST_SEGMENT_MS = 400L
private const val SECOND_SEGMENT_MS = 400L
private const val BOOK_DURATION_MS = FIRST_SEGMENT_MS + SECOND_SEGMENT_MS

/** Decoders round; the claim under test is "past the boundary", not "to the millisecond". */
private const val TOLERANCE_MS = 150L

private const val SEEK_MS = 500L
private const val SINGLE_SEGMENT_MS = 1_000L

/**
 * The surface that only exists once audio really runs: crossing a segment boundary, settling on a
 * state when nobody pressed play, and going properly quiet on release.
 *
 * These need a browser that will start audio without a tap, which is not the default — both lanes
 * pass `--autoplay-policy=no-user-gesture-required` (`karma.config.d/autoplay.js` and
 * `web/test/run-kotest.mjs`). Before that flag existed every `play()` here rejected and this whole
 * area was untested rather than failing, which is the harder kind of gap to notice.
 */
class HtmlAudioPlayerLifecycleTest :
    FunSpec({

        test("playback crosses the segment boundary and finishes the book") {
            val first = silentSegment(FIRST_SEGMENT_MS, offsetMs = 0)
            val second = silentSegment(SECOND_SEGMENT_MS, offsetMs = FIRST_SEGMENT_MS)
            val player = HtmlAudioPlayer()
            player.load(listOf(first, second))

            player.play()
            player.awaitState(PlaybackState.Ended)

            // Reaching Ended at a book-relative position past the first segment is the proof that
            // segment two was attached and played, rather than the book stopping at the seam.
            player.positionMs.value.shouldBeBetween(FIRST_SEGMENT_MS, BOOK_DURATION_MS + TOLERANCE_MS)
            player.durationMs.value shouldBe BOOK_DURATION_MS

            player.releasePlayer()
            URL.revokeObjectURL(first.url)
            URL.revokeObjectURL(second.url)
        }

        test("a book that is loaded and never played reports Paused, not a spinner") {
            val segment = silentSegment(SINGLE_SEGMENT_MS)
            val player = HtmlAudioPlayer()

            player.load(listOf(segment))

            // `attach` publishes Buffering optimistically. Nothing sends a `pause` to an element
            // that was never playing, so before `canplay` was subscribed this sat on Buffering for
            // the life of the tab and PlaybackManager's spinner sat with it.
            player.awaitState(PlaybackState.Paused)

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        test("release zeroes everything a UI might still be observing") {
            val segment = silentSegment(SINGLE_SEGMENT_MS)
            val player = HtmlAudioPlayer()
            player.load(listOf(segment))
            player.seekTo(SEEK_MS)
            player.awaitState(PlaybackState.Paused)

            player.releasePlayer()

            player.state.value shouldBe PlaybackState.Idle
            player.positionMs.value shouldBe 0L
            player.durationMs.value shouldBe 0L

            URL.revokeObjectURL(segment.url)
        }

        test("a refused play is a reported error, and an interrupted one is not") {
            // The policy path itself cannot be exercised here: both lanes deliberately permit
            // autoplay so the specs above can run at all, so no play() in this suite is ever
            // refused. What is testable — and what the bug actually was — is the decision made
            // about a rejection once it arrives, so that is asserted directly rather than staged.
            playRefusalMessage("NotAllowedError").shouldNotBeNull()
            playRefusalMessage("NotSupportedError").shouldNotBeNull()

            // A segment advance re-points the element while a play is pending, which rejects with
            // AbortError every time. Reporting that would paint an error over correct playback —
            // the boundary-crossing spec above would fail the moment it stopped being filtered.
            playRefusalMessage("AbortError") shouldBe null
        }
    })
