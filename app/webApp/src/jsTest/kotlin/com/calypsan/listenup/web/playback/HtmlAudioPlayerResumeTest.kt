package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlaybackState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.w3c.dom.url.URL

private const val SEGMENT_DURATION_MS = 1_500L
private const val RESUME_MS = 750L

/** How long a spec waits for a freshly loaded book to settle on Paused or Playing. */
private const val SETTLE_TIMEOUT_MS = 5_000L

/** How long a stale start instruction would have had to act, once the load has settled. */
private const val SETTLE_GRACE_MS = 600L

/** A seek lands on a sample boundary, not a millisecond one; ±100 ms is far below the 750 ms gap. */
private const val TOLERANCE_MS = 100L

/**
 * Losing a listener's place is the one failure this app cannot afford, and `load()` followed by
 * `seekTo()` — what `PlaybackManagerImpl` does on every resume — is the path it travels.
 *
 * For a single-file `.m4b`, which is most audiobooks, that seek stays inside segment 0. A version
 * of this player recorded the resume offset only on attach, so the same-segment branch left the
 * recorded value at zero and `loadedmetadata` rewound the book to the beginning — after
 * `positionMs` had already reported the correct place, which is the worst kind of wrong.
 */
class HtmlAudioPlayerResumeTest :
    FunSpec({

        test("a resume inside the first segment survives the element becoming ready") {
            val segment = silentSegment(SEGMENT_DURATION_MS)
            val player = HtmlAudioPlayer()
            player.load(listOf(segment))

            player.seekTo(RESUME_MS)

            // The regression fired at `loadedmetadata`, so a spec that finished before the element
            // got there would pass without ever entering the window it guards. Waiting for `Paused`
            // proves the element reached `canplay`, which is strictly past `loadedmetadata` — and
            // times out loudly rather than passing vacuously if it never does.
            player.awaitState(PlaybackState.Paused)
            player.positionMs.value.shouldBeBetween(RESUME_MS - TOLERANCE_MS, RESUME_MS + TOLERANCE_MS)

            // Nothing may quietly move it afterwards either — proved by a state the element must
            // actually reach, not by sampling a clock. Playing is strictly after `canplay`, and a
            // rewind to zero would put the position BELOW the resume point.
            player.play()
            player.awaitState(PlaybackState.Playing)
            player.positionMs.value shouldBeGreaterThanOrEqual RESUME_MS - TOLERANCE_MS

            player.pause()
            player.awaitState(PlaybackState.Paused)
            player.positionMs.value shouldBeGreaterThanOrEqual RESUME_MS - TOLERANCE_MS

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        // The listener asked for book A, not for whatever is loaded next: only priming (the tap that asked for a book
        // before its audio arrives) lets the following load start by itself.
        test("a load that follows play() without a prime does not start by itself") {
            val first = silentSegment(SEGMENT_DURATION_MS)
            val second = silentSegment(SEGMENT_DURATION_MS)
            val player = HtmlAudioPlayer()
            player.load(listOf(first))
            player.play()
            player.awaitState(PlaybackState.Playing)

            player.load(listOf(second))

            // Settled on either outcome first, then given the time a stale instruction would need to start it.
            withTimeout(SETTLE_TIMEOUT_MS) {
                player.state.first { it == PlaybackState.Paused || it == PlaybackState.Playing }
            }
            delay(SETTLE_GRACE_MS)
            player.state.value shouldBe PlaybackState.Paused

            player.releasePlayer()
            URL.revokeObjectURL(first.url)
            URL.revokeObjectURL(second.url)
        }

        test("a load that follows primeForPlayback starts on ready") {
            val segment = silentSegment(SEGMENT_DURATION_MS)
            val player = HtmlAudioPlayer()

            player.primeForPlayback()
            player.load(listOf(segment))

            player.awaitState(PlaybackState.Playing)

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        test("priming is spent by the load it was for: the load after that does not start by itself") {
            val first = silentSegment(SEGMENT_DURATION_MS)
            val second = silentSegment(SEGMENT_DURATION_MS)
            val player = HtmlAudioPlayer()
            player.primeForPlayback()
            player.load(listOf(first))
            player.awaitState(PlaybackState.Playing)
            player.pause()
            player.awaitState(PlaybackState.Paused)

            player.load(listOf(second))

            withTimeout(SETTLE_TIMEOUT_MS) {
                player.state.first { it == PlaybackState.Paused || it == PlaybackState.Playing }
            }
            delay(SETTLE_GRACE_MS)
            player.state.value shouldBe PlaybackState.Paused

            player.releasePlayer()
            URL.revokeObjectURL(first.url)
            URL.revokeObjectURL(second.url)
        }
    })
