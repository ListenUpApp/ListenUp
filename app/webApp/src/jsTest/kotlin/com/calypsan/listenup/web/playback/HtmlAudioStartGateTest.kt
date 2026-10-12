package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlaybackState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.url.URL

private const val GATE_SEGMENT_MS = 1_500L

/**
 * The browser's start gate over the real [HtmlAudioPlayer].
 *
 * `pause()` on a primed, still-empty element does not publish `Paused` in the same task (the
 * element's `pause` event arrives a task later), so what [HtmlAudioStartGate.withdraw] is pinned
 * on is the behaviour it exists for: a load that arrives after it settles paused instead of
 * playing on the strength of a tap that went nowhere. The control spec proves that assertion
 * can fail.
 */
class HtmlAudioStartGateTest :
    FunSpec({
        test("prime arms the element, withdraw takes the intent back, release forgets it") {
            val player = HtmlAudioPlayer()
            val gate = HtmlAudioStartGate(player)
            val segment = silentSegment(GATE_SEGMENT_MS)

            gate.prime()
            player.state.value shouldBe PlaybackState.Buffering
            gate.withdraw()

            player.load(listOf(segment))
            player.awaitState(PlaybackState.Paused)

            gate.release()
            player.state.value shouldBe PlaybackState.Idle

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        test("control: a prime nobody withdraws plays the next load") {
            val player = HtmlAudioPlayer()
            val gate = HtmlAudioStartGate(player)
            val segment = silentSegment(GATE_SEGMENT_MS)

            gate.prime()
            player.load(listOf(segment))
            player.awaitState(PlaybackState.Playing)

            gate.release()
            player.state.value shouldBe PlaybackState.Idle

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }
    })
