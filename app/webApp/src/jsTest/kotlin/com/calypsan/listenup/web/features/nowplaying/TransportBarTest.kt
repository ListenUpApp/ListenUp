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

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
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

        test("the scrubber reports the position the listener dragged to") {
            var seekedTo = -1L
            val host =
                mount {
                    TransportBar(
                        state = TransportState("Dune", isPlaying = true, positionMs = 0, durationMs = SHORT_BOOK_MS),
                        onPlayPause = {},
                        onSeek = { seekedTo = it },
                    )
                }

            val scrubber = host.querySelector(".tport-scrub") as HTMLInputElement
            scrubber.getAttribute("max") shouldBe SHORT_BOOK_MS.toString()
            scrubber.value = SEEK_TARGET_MS.toString()
            scrubber.dispatchEvent(Event("input"))

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

        test("a bar with a state still renders its title") {
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
            host.querySelectorAll(".tport-time").length shouldBe 2
        }
    })
