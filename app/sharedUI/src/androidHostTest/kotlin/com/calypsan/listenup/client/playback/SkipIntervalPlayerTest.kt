package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Pins ExoPlayer's `COMMAND_SEEK_BACK`/`COMMAND_SEEK_FORWARD` increments to the user's
 * configured skip intervals (#1300).
 *
 * ## Why a wrapper rather than the builder
 *
 * `ExoPlayer.Builder.setSeekForwardIncrementMs` is builder-only — there is no runtime setter —
 * so honouring a settings change through the builder would mean rebuilding the player, which
 * interrupts playback mid-sentence. [SkipIntervalPlayer] reads the live value on every call
 * instead, so a change lands on the very next press with the same player instance still
 * sounding.
 *
 * These commands are what a car's steering-wheel skip buttons and a Wear tile invoke, so the
 * increments must also be *reported* truthfully: `ForwardingSimpleBasePlayer` copies
 * `getSeekBackIncrement()`/`getSeekForwardIncrement()` into the state every connected controller
 * reads, and a controller that renders "30" while seeking 45 is a lie the user meets in a car.
 */
class SkipIntervalPlayerTest :
    FunSpec({
        /** 10-minute file; the fake's position sits well inside it so a clamp is never accidental. */
        fun fake(positionMs: Long = 300_000L) = FakeExoPlayer(stubbedPosition = positionMs, stubbedDuration = 600_000L)

        test("reports the configured increments, so controllers render the truth") {
            val player = SkipIntervalPlayer(fake(), forwardMs = { 45_000L }, backwardMs = { 20_000L })

            player.seekForwardIncrement shouldBe 45_000L
            player.seekBackIncrement shouldBe 20_000L
        }

        test("seekForward moves by the configured interval, not Media3's or our old 30 seconds") {
            val underlying = fake()
            val player = SkipIntervalPlayer(underlying, forwardMs = { 45_000L }, backwardMs = { 20_000L })

            player.seekForward()

            underlying.fileRelativeSeekCalls shouldBe listOf(345_000L)
            underlying.seekCalls.shouldBeEmpty()
        }

        test("seekBack moves by the configured interval, not the old 10 seconds") {
            val underlying = fake()
            val player = SkipIntervalPlayer(underlying, forwardMs = { 45_000L }, backwardMs = { 20_000L })

            player.seekBack()

            underlying.fileRelativeSeekCalls shouldBe listOf(280_000L)
        }

        test("a settings change lands on the next press without rebuilding the player") {
            var forward = 15_000L
            val underlying = fake()
            val player = SkipIntervalPlayer(underlying, forwardMs = { forward }, backwardMs = { 10_000L })

            player.seekForward()
            forward = 90_000L
            player.seekForward()

            // The same instance served both — a rebuild would have been a new player, and the
            // second value proves the increment is read per call rather than latched.
            player.seekForwardIncrement shouldBe 90_000L
            underlying.fileRelativeSeekCalls shouldBe listOf(315_000L, 390_000L)
        }

        test("a forward skip clamps to the end of the media rather than seeking past it") {
            val underlying = fake(positionMs = 599_000L)
            val player = SkipIntervalPlayer(underlying, forwardMs = { 120_000L }, backwardMs = { 10_000L })

            player.seekForward()

            underlying.fileRelativeSeekCalls shouldBe listOf(600_000L)
        }

        test("a backward skip clamps to zero rather than seeking negative") {
            val underlying = fake(positionMs = 5_000L)
            val player = SkipIntervalPlayer(underlying, forwardMs = { 30_000L }, backwardMs = { 60_000L })

            player.seekBack()

            underlying.fileRelativeSeekCalls shouldBe listOf(0L)
        }
    })
