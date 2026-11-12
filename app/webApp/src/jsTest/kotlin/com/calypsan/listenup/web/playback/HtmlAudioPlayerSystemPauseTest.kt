package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlaybackState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.url.URL

private const val LONG_SEGMENT_MS = 1_500L
private const val SHORT_SEGMENT_MS = 400L
private const val SHORT_BOOK_MS = SHORT_SEGMENT_MS * 2

/** A jump into the second file, the way a chapter skip crosses a file boundary in a multi-file book. */
private const val INTO_SECOND_SEGMENT_MS = LONG_SEGMENT_MS + 200L

/** How long a spec waits for a re-pointed element to settle on Paused or Playing. */
private const val SETTLE_TIMEOUT_MS = 5_000L

/** How long a stale start instruction would have had to act, once the element has settled. */
private const val SETTLE_GRACE_MS = 600L

/** Decoders round; the claim under test is "reached the end", not "to the millisecond". */
private const val TOLERANCE_MS = 150L

/**
 * A pause the browser makes on its own — the output device going away, which Chrome on Android and Safari on
 * iOS answer by pausing the element — is a pause the listener did not ask to undo. Only their own play may end it.
 *
 * The browser's pause is staged by pausing the element directly, from outside the player, which is exactly
 * what the browser does: the player hears a `pause` event it did not cause.
 */
class HtmlAudioPlayerSystemPauseTest :
    FunSpec({

        test("after the browser pauses the book, a jump into another file stays paused") {
            val first = silentSegment(LONG_SEGMENT_MS, offsetMs = 0)
            val second = silentSegment(LONG_SEGMENT_MS, offsetMs = LONG_SEGMENT_MS)
            val element = document.createElement("audio") as HTMLAudioElement
            val player = HtmlAudioPlayer(element = element)
            player.load(listOf(first, second))
            player.play()
            player.awaitState(PlaybackState.Playing)

            element.pause()
            player.awaitState(PlaybackState.Paused)
            player.seekTo(INTO_SECOND_SEGMENT_MS)

            withTimeout(SETTLE_TIMEOUT_MS) {
                player.state.first { it == PlaybackState.Paused || it == PlaybackState.Playing }
            }
            delay(SETTLE_GRACE_MS)
            player.state.value shouldBe PlaybackState.Paused
            element.paused shouldBe true

            player.releasePlayer()
            URL.revokeObjectURL(first.url)
            URL.revokeObjectURL(second.url)
        }

        // The guard's other edge: the `pause` event from the player's own pause() is dispatched after a play()
        // issued in the same breath, so a player that read every `pause` event as the browser's would drop the
        // listener's play and stop at the next file boundary.
        test("a pause the listener takes back at once still plays on across the file boundary") {
            val first = silentSegment(SHORT_SEGMENT_MS, offsetMs = 0)
            val second = silentSegment(SHORT_SEGMENT_MS, offsetMs = SHORT_SEGMENT_MS)
            val player = HtmlAudioPlayer()
            player.load(listOf(first, second))
            player.play()
            player.awaitState(PlaybackState.Playing)

            player.pause()
            player.play()

            player.awaitState(PlaybackState.Ended)
            player.positionMs.value.shouldBeBetween(
                SHORT_BOOK_MS - TOLERANCE_MS,
                SHORT_BOOK_MS + TOLERANCE_MS,
            )

            player.releasePlayer()
            URL.revokeObjectURL(first.url)
            URL.revokeObjectURL(second.url)
        }
    })
