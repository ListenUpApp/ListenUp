package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [ActiveTransport] across a whole Cast session. A hand-off detaches the outgoing player's
 * listener, so that player's stop never reaches the service; when the incoming player did not
 * take over the sound, "audio is sounding" stayed true and every later play was ignored, so no
 * listen was recorded until the service was recreated.
 */
class ActiveTransportTest :
    FunSpec({

        test("a cast stop the service never heard does not swallow the next local play") {
            val transport = ActiveTransport(PlaybackRefusalTracker())
            var listensEnded = 0

            transport.onIsPlayingChanged(TransportSource.LOCAL, isPlaying = true) shouldBe PlaybackTransition.START
            transport.handOff(TransportSource.CAST) { listensEnded++ }
            transport.onIsPlayingChanged(TransportSource.CAST, isPlaying = true) shouldBe PlaybackTransition.START

            // The receiver drops while playing; the local player comes back paused, and the cast
            // player's stop arrives only after the hand-off, from the player that is no longer the transport.
            transport.handOff(TransportSource.LOCAL) { listensEnded++ }
            transport.onIsPlayingChanged(TransportSource.CAST, isPlaying = false) shouldBe PlaybackTransition.IGNORE

            transport.onIsPlayingChanged(TransportSource.LOCAL, isPlaying = true) shouldBe PlaybackTransition.START
            listensEnded shouldBe 2
        }

        test("a hand-off ends the outgoing listen while the outgoing player is still the transport") {
            val transport = ActiveTransport(PlaybackRefusalTracker())
            transport.onIsPlayingChanged(TransportSource.LOCAL, isPlaying = true)

            var castingWhenEnded: Boolean? = null
            transport.handOff(TransportSource.CAST) { castingWhenEnded = transport.casting }

            castingWhenEnded shouldBe false
            transport.casting shouldBe true
        }

        test("a hand-off with nothing sounding ends no listen") {
            val transport = ActiveTransport(PlaybackRefusalTracker())
            var listensEnded = 0

            transport.handOff(TransportSource.CAST) { listensEnded++ }
            transport.handOff(TransportSource.LOCAL) { listensEnded++ }

            listensEnded shouldBe 0
            transport.onIsPlayingChanged(TransportSource.LOCAL, isPlaying = true) shouldBe PlaybackTransition.START
        }

        test("the local player pausing for the hand-off is still ignored") {
            val transport = ActiveTransport(PlaybackRefusalTracker())
            transport.onIsPlayingChanged(TransportSource.LOCAL, isPlaying = true)
            transport.handOff(TransportSource.CAST) {}

            transport.onIsPlayingChanged(TransportSource.LOCAL, isPlaying = false) shouldBe PlaybackTransition.IGNORE
        }
    })
