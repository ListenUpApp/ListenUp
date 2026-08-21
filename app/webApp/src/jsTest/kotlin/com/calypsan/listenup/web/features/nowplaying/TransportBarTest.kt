package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import com.calypsan.listenup.web.playback.WebPlaybackController
import com.calypsan.listenup.web.playback.awaitState
import com.calypsan.listenup.web.playback.silentSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL

/** A book long enough that the elapsed label crosses into hours. */
private const val LONG_BOOK_MS = 9_000_000L

private const val ONE_HOUR_MS = 3_600_000L

private const val UNDER_AN_HOUR_MS = 125_000L

private const val SHORT_BOOK_MS = 600_000L

private const val SEEK_TARGET_MS = 420_000L

/** Long enough for the element to run a measurable clock, short enough to keep the lane quick. */
private const val AUDIO_SEGMENT_MS = 1_500L

private const val PLAYING_TIMEOUT_MS = 15_000L

/** How many `input` events one drag is stood in for. Any number above one makes the point. */
private const val DRAG_SAMPLES = 5

/**
 * Every host this spec has mounted, so [TransportBarTest] can take them back out again.
 *
 * The page is shared by two hundred other specs, and a mount that is never removed leaves an
 * orphan subtree on it for the rest of the run — which a later `document.querySelector` will
 * happily find.
 */
private val mountedHosts = mutableListOf<HTMLElement>()

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    mountedHosts += host
    renderComposable(root = host) { content() }
    return host
}

/**
 * The transport bar's own contract — and, more importantly, the one thing a DOM assertion cannot
 * see: whether pressing its play control makes any sound.
 *
 * Every layer under this bar reports success without playing anything. `PlaybackManagerImpl`
 * loads, sets speed and seeks; neither browser player auto-plays on `load()`. So a bar wired
 * naively to a prepare would show the right book, at the right position, in a coherent `Paused`
 * state, in silence — with no error anywhere to explain it. The two audio specs at the bottom are
 * the ones that would fail if that gap ever reopened.
 */
class TransportBarTest :
    FunSpec({

        afterSpec {
            mountedHosts.forEach { it.remove() }
            mountedHosts.clear()
        }

        test("a paused book offers Play, and a playing one offers Pause") {
            val paused =
                mount {
                    TransportBar(
                        state = TransportState("Dune", isPlaying = false, positionMs = 0, durationMs = SHORT_BOOK_MS),
                        onPlayPause = {},
                        onSeek = {},
                    )
                }
            val playing =
                mount {
                    TransportBar(
                        state = TransportState("Dune", isPlaying = true, positionMs = 0, durationMs = SHORT_BOOK_MS),
                        onPlayPause = {},
                        onSeek = {},
                    )
                }

            paused.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Play"
            playing.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Pause"
        }

        test("the play control reports exactly one click") {
            var clicks = 0
            val host =
                mount {
                    TransportBar(
                        state = TransportState("Dune", isPlaying = false, positionMs = 0, durationMs = SHORT_BOOK_MS),
                        onPlayPause = { clicks++ },
                        onSeek = {},
                    )
                }

            (host.querySelector(".tport-b") as HTMLElement).click()

            clicks shouldBe 1
        }

        test("elapsed time reads H:MM:SS at an hour and M:SS below it") {
            // The boundary is the whole claim: one second either side of an hour must not render
            // the same shape, or a nine-hour book reads as nine minutes.
            formatElapsed(UNDER_AN_HOUR_MS) shouldBe "2:05"
            formatElapsed(ONE_HOUR_MS) shouldBe "1:00:00"
            formatElapsed(ONE_HOUR_MS - 1_000) shouldBe "59:59"
            formatElapsed(LONG_BOOK_MS) shouldBe "2:30:00"
        }

        test("with no book loaded the bar renders nothing at all") {
            // An empty transport bar is chrome that lies about there being something to play.
            val host = mount { TransportBar(state = null, onPlayPause = {}, onSeek = {}) }

            host.querySelector(".tport") shouldBe null
            host.textContent.orEmpty() shouldBe ""
        }

        test("the scrubber seeks once on release, not once per pointer sample") {
            // `input` fires continuously during a drag. Seeking on each one would have
            // HtmlAudioPlayer re-run `attach` per sample — and on the HLS path that destroys and
            // rebuilds an hls.js instance, playlists and all, tens of times a second.
            var seeks = 0
            var seekedTo = -1L
            val host =
                mount {
                    TransportBar(
                        state = TransportState("Dune", isPlaying = true, positionMs = 0, durationMs = SHORT_BOOK_MS),
                        onPlayPause = {},
                        onSeek = {
                            seeks++
                            seekedTo = it
                        },
                    )
                }

            val scrubber = host.querySelector(".tport-scrub") as HTMLInputElement
            scrubber.getAttribute("max") shouldBe SHORT_BOOK_MS.toString()
            repeat(DRAG_SAMPLES) { sample ->
                scrubber.value = (SEEK_TARGET_MS + sample).toString()
                scrubber.dispatchEvent(Event("input"))
            }

            seeks shouldBe 0

            scrubber.value = SEEK_TARGET_MS.toString()
            scrubber.dispatchEvent(Event("change"))

            seeks shouldBe 1
            seekedTo shouldBe SEEK_TARGET_MS
        }

        test("a book loaded after the gesture actually plays, rather than settling on Paused") {
            // The regression this whole spec file exists for. `HtmlAudioPlayerLifecycleTest`
            // already pins the other half — a bare `load()` settles on Paused — so if priming
            // stopped carrying the listener's intent across the prepare, this is the only thing
            // in the suite that would notice, and it would notice as silence.
            val player = HtmlAudioPlayer()
            val segment = silentSegment(AUDIO_SEGMENT_MS)

            player.primeForPlayback()
            player.load(listOf(segment))

            player.awaitState(PlaybackState.Playing)

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        test("the Play control drives a real prepare all the way to audible playback") {
            val segment = silentSegment(AUDIO_SEGMENT_MS)
            val player = HtmlAudioPlayer()
            val manager = fakePlaybackManager(segment, title = "Dune")
            val playback =
                LivePlayback(
                    playbackManager = manager,
                    playbackController = WebPlaybackController(player, manager),
                    audioPlayer = player,
                )

            // Exactly what Book Detail's Play button does.
            playback.playBook(BookId("book-1"))

            player.awaitState(PlaybackState.Playing)
            // Not merely "Playing was reported": the element's own clock has to have moved, which
            // is the difference between a state machine that agrees with itself and audio output.
            withTimeout(PLAYING_TIMEOUT_MS) { player.positionMs.first { it > 0L } } shouldBeGreaterThan 0L

            val state = withTimeout(PLAYING_TIMEOUT_MS) { playback.state.first { it?.isPlaying == true } }
            state.shouldNotBeNull().title shouldBe "Dune"

            // …and the bar built from that state offers the way to stop it.
            val host = mount { TransportBar(state = state, onPlayPause = playback::playPause, onSeek = playback::seek) }
            host.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Pause"
            host.textContent.orEmpty() shouldContain "Dune"

            playback.close()
            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        test("the bar renders elapsed on the left of the scrubber and total on the right") {
            // The two labels are deliberately different values, and asserted in order. Counting
            // them proves nothing: swapping `positionMs` and `durationMs` at the call site renders
            // two labels too, and would tell every listener their book was nearly over.
            val host =
                mount {
                    TransportBar(
                        state =
                            TransportState(
                                title = "Dune",
                                isPlaying = false,
                                positionMs = UNDER_AN_HOUR_MS,
                                durationMs = LONG_BOOK_MS,
                            ),
                        onPlayPause = {},
                        onSeek = {},
                    )
                }

            host.querySelector(".tport-t")!!.textContent shouldBe "Dune"
            val times = host.querySelectorAll(".tport-time")
            times.length shouldBe 2
            times.item(0)!!.textContent shouldBe formatElapsed(UNDER_AN_HOUR_MS)
            times.item(1)!!.textContent shouldBe formatElapsed(LONG_BOOK_MS)
            // …and not the same string, or the assertions above would survive a swap.
            formatElapsed(UNDER_AN_HOUR_MS) shouldNotBe formatElapsed(LONG_BOOK_MS)
        }

        test("a prepare that throws withdraws the primed intent") {
            // A prime is a standing instruction on a singleton player. Left set, it makes the NEXT
            // book to load start playing on the strength of a tap that went nowhere — and
            // `HtmlAudioPlayerLifecycleTest`'s "settles on Paused" cannot catch it, because that
            // spec builds a fresh player whose flag was never set.
            val player = HtmlAudioPlayer()
            val doomed = silentSegment(AUDIO_SEGMENT_MS)
            val manager = fakePlaybackManager(doomed, title = "Dune", prepare = PrepareOutcome.THROWS)
            val playback = LivePlayback(manager, WebPlaybackController(player, manager), player)

            playback.playBook(BookId("book-1"))
            // The window closing is the `finally` having run — deterministic, unlike a delay.
            withTimeout(PLAYING_TIMEOUT_MS) { manager.preparingBookId.first { it == null } }

            val unrelated = silentSegment(AUDIO_SEGMENT_MS)
            player.load(listOf(unrelated))

            player.awaitState(PlaybackState.Paused)

            playback.close()
            player.releasePlayer()
            URL.revokeObjectURL(doomed.url)
            URL.revokeObjectURL(unrelated.url)
        }

        test("closing the session mid-prepare withdraws the primed intent") {
            // Sign-out and any auth-state flip unmount the shell, which closes the session while a
            // prepare may still be in flight. Cancellation unwinds through the same `finally`.
            val player = HtmlAudioPlayer()
            val pending = silentSegment(AUDIO_SEGMENT_MS)
            val manager = fakePlaybackManager(pending, title = "Dune", prepare = PrepareOutcome.NEVER_RETURNS)
            val playback = LivePlayback(manager, WebPlaybackController(player, manager), player)

            playback.playBook(BookId("book-1"))
            playback.close()
            withTimeout(PLAYING_TIMEOUT_MS) { manager.preparingBookId.first { it == null } }

            val unrelated = silentSegment(AUDIO_SEGMENT_MS)
            player.load(listOf(unrelated))

            player.awaitState(PlaybackState.Paused)

            player.releasePlayer()
            URL.revokeObjectURL(pending.url)
            URL.revokeObjectURL(unrelated.url)
        }

        test("priming for a new book stops the bar naming the old one at zero") {
            // primeForPlayback drops the loaded content, so position and duration are already zero.
            // Keeping the previous title beside them would report a real book parked at 0:00 for
            // the whole round-trip.
            val player = HtmlAudioPlayer()
            val segment = silentSegment(AUDIO_SEGMENT_MS)
            //
            // ONE session across both books, deliberately. A second LivePlayback would start with
            // its title already null, and the assertion below would hold whether or not `playBook`
            // ever cleared anything — proof of nothing.
            val manager = fakePlaybackManager(segment, title = "Dune", prepare = PrepareOutcome.STALLS_AFTER_FIRST)
            val playback = LivePlayback(manager, WebPlaybackController(player, manager), player)

            playback.playBook(BookId("book-1"))
            withTimeout(PLAYING_TIMEOUT_MS) { playback.state.first { it != null } }

            // The second book's prepare is still in flight, which is the whole window under test.
            playback.playBook(BookId("book-2"))

            withTimeout(PLAYING_TIMEOUT_MS) { playback.state.first { it == null } }

            playback.close()
            URL.revokeObjectURL(segment.url)
        }

        test("closing the session stops the audio it owns") {
            // Sign-out unmounts the shell, which closes the session. Nothing else on this platform
            // reaches the element: PlaybackManagerImpl.clearPlayback only zeroes its own flows,
            // WebPlaybackController.releasePlayer is a documented no-op, and Koin's onClose fires
            // at graph teardown. Without this, the book narrates on over the login screen with no
            // transport bar left to stop it.
            val player = HtmlAudioPlayer()
            val segment = silentSegment(AUDIO_SEGMENT_MS)
            val manager = fakePlaybackManager(segment, title = "Dune")
            val playback = LivePlayback(manager, WebPlaybackController(player, manager), player)

            playback.playBook(BookId("book-1"))
            player.awaitState(PlaybackState.Playing)

            playback.close()

            player.state.value shouldBe PlaybackState.Idle
            player.isPaused shouldBe true

            URL.revokeObjectURL(segment.url)
        }

        test("asking to play the book already playing resumes it instead of restarting it") {
            // Falling through to a prime would unload the audio mid-sentence, re-run the prepare
            // and resume from the persisted position — a drop-out plus up to ten seconds of
            // rewind, for a tap that should cost nothing.
            val player = HtmlAudioPlayer()
            val segment = silentSegment(AUDIO_SEGMENT_MS)
            val manager = fakePlaybackManager(segment, title = "Dune")
            val playback = LivePlayback(manager, WebPlaybackController(player, manager), player)

            playback.playBook(BookId("book-1"))
            // Wait on the manager's flow, not the player's: `playPause` branches on exactly this
            // value, and so does the bar's own Play/Pause icon — they agree by construction.
            withTimeout(PLAYING_TIMEOUT_MS) { manager.isPlaying.first { it } }
            playback.playPause()
            withTimeout(PLAYING_TIMEOUT_MS) { manager.isPlaying.first { !it } }

            playback.playBook(BookId("book-1"))

            // Synchronous proof that no restart happened: a prime runs `forgetContent()`, which
            // zeroes this before `playBook` returns. Asserting it here needs no timing at all.
            player.durationMs.value shouldBe AUDIO_SEGMENT_MS
            player.awaitState(PlaybackState.Playing)

            playback.close()
            URL.revokeObjectURL(segment.url)
        }

        test("a failed prepare is reported to the listener rather than swallowed") {
            val player = HtmlAudioPlayer()
            val segment = silentSegment(AUDIO_SEGMENT_MS)
            val manager = fakePlaybackManager(segment, title = "Dune", prepare = PrepareOutcome.THROWS)
            val playback = LivePlayback(manager, WebPlaybackController(player, manager), player)

            playback.playBook(BookId("book-1"))

            val message = withTimeout(PLAYING_TIMEOUT_MS) { playback.error.first { it != null } }
            val host = mount { PlaybackNotice(message = message, onDismiss = playback::dismissError) }
            host.querySelector(".tport-note")!!.textContent.orEmpty() shouldContain "Couldn't start this book"

            playback.close()
            URL.revokeObjectURL(segment.url)
        }

        test("a notice with nothing to report renders nothing") {
            val host = mount { PlaybackNotice(message = null, onDismiss = {}) }

            host.querySelector(".tport-note") shouldBe null
        }

        test("the scrubber steps by a second, not a millisecond, and says the time out loud") {
            // step defaults to 1 on a range input — one millisecond here, so an arrow key would
            // need thirty thousand presses to move half a minute. aria-valuenow is equally raw.
            val host =
                mount {
                    TransportBar(
                        state =
                            TransportState("Dune", isPlaying = true, positionMs = SEEK_TARGET_MS, durationMs = SHORT_BOOK_MS),
                        onPlayPause = {},
                        onSeek = {},
                    )
                }

            val scrubber = host.querySelector(".tport-scrub") as HTMLInputElement
            scrubber.getAttribute("step") shouldBe "1000"
            scrubber.getAttribute("aria-valuetext") shouldBe formatElapsed(SEEK_TARGET_MS)
        }
    })
