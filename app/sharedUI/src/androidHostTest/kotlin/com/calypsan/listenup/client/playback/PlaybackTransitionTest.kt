package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [playbackTransitionFor] — the rule deciding whether an is-playing event should
 * start, stop, or be ignored by the service's progress bookkeeping.
 *
 * The bug that motivates this: bookkeeping was driven exclusively by the **local** player's
 * listener, and nothing was ever attached to the cast player. Casting from a paused state
 * therefore recorded nothing at all for the whole session — no position persistence, no
 * listening span, and an in-app seek bar frozen where the listener left it.
 */
class PlaybackTransitionTest :
    FunSpec({

        // ── local player drives bookkeeping while not casting ─────────────────

        test("local player starting playback starts bookkeeping") {
            playbackTransitionFor(
                TransportSource.LOCAL,
                isPlaying = true,
                casting = false,
                spanOpen = false,
            ) shouldBe PlaybackTransition.START
        }

        test("local player pausing stops bookkeeping") {
            playbackTransitionFor(
                TransportSource.LOCAL,
                isPlaying = false,
                casting = false,
                spanOpen = true,
            ) shouldBe PlaybackTransition.STOP
        }

        // ── cast player drives bookkeeping while casting ──────────────────────

        test("cast player starting playback from a paused state starts bookkeeping") {
            // The regression: a cast session begun from a paused state never produced this
            // transition at all, because only the local player was ever listened to.
            playbackTransitionFor(
                TransportSource.CAST,
                isPlaying = true,
                casting = true,
                spanOpen = false,
            ) shouldBe PlaybackTransition.START
        }

        test("cast player pausing on the receiver stops bookkeeping") {
            playbackTransitionFor(
                TransportSource.CAST,
                isPlaying = false,
                casting = true,
                spanOpen = true,
            ) shouldBe PlaybackTransition.STOP
        }

        test("cast taking over from playing local audio continues the open span") {
            // Casting mid-listen must NOT re-announce play: opening a span on top of an open one
            // replaces it, discarding everything listened to before the hand-off.
            playbackTransitionFor(
                TransportSource.CAST,
                isPlaying = true,
                casting = true,
                spanOpen = true,
            ) shouldBe PlaybackTransition.IGNORE
        }

        // ── events from the inactive player are ignored ───────────────────────

        test("local player pausing for a cast handoff is not a real pause") {
            // Handing off to Cast releases local audio focus, which pauses the local player.
            // Treating that as a pause would close the listening span the cast session is
            // about to continue, and would arm the idle timer that tears the service down.
            playbackTransitionFor(
                TransportSource.LOCAL,
                isPlaying = false,
                casting = true,
                spanOpen = true,
            ) shouldBe PlaybackTransition.IGNORE
        }

        test("local player sounding while casting does not hijack the session") {
            playbackTransitionFor(
                TransportSource.LOCAL,
                isPlaying = true,
                casting = true,
                spanOpen = true,
            ) shouldBe PlaybackTransition.IGNORE
        }

        test("a late cast event after handoff back to local is ignored") {
            // Cast teardown can deliver a trailing is-playing=false after the session has
            // already swapped home. Acting on it would stop bookkeeping for a local session
            // that is playing perfectly well.
            playbackTransitionFor(
                TransportSource.CAST,
                isPlaying = false,
                casting = false,
                spanOpen = true,
            ) shouldBe PlaybackTransition.IGNORE
            playbackTransitionFor(
                TransportSource.CAST,
                isPlaying = true,
                casting = false,
                spanOpen = false,
            ) shouldBe PlaybackTransition.IGNORE
        }
    })
