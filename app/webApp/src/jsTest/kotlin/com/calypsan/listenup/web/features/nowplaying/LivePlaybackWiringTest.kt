package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.web.createSqliteWorker
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import com.calypsan.listenup.web.playback.awaitState
import com.calypsan.listenup.web.playback.silentSegment
import com.calypsan.listenup.web.startWebKoin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.w3c.dom.Worker
import org.w3c.dom.url.URL
import kotlin.math.abs

/**
 * The browser's player, wired the way the shell wires it: the real Koin graph ([startWebKoin], so the
 * shared `PlayerSession` exists before anyone asks), the real `<audio>` element and its start gate, and
 * the shell's [PlaybackSession] from [graphPlayback]. Only the prepare (an RPC) and the book's Room row
 * are stood in for.
 *
 * The rules themselves (supersede, same-book resume, sleep, speed) are pinned once, in sharedLogic's
 * `PlayerSession*Test`. What only a browser can prove lives here: the tap is spent on the element inside
 * the click, sound actually comes out, the gain stage hears a boost, and every way of stopping lets go of
 * the element.
 */
class LivePlaybackWiringTest :
    FunSpec({
        afterTest {
            GlobalContext.getOrNull()?.let { koin ->
                // The loaded book is a localStorage key: left behind, the next spec's graph would restore it.
                koin.get<LoadedBookRepository>().clear()
                koin.get<HtmlAudioPlayer>().releasePlayer()
            }
            stopKoin()
        }

        test("Play spends the tap on the audio element before anything suspends, then the book loads") {
            val segment = silentSegment(SEGMENT_MS)
            val koin = bootBrowserGraph(fakePlaybackManager(segment, title = DUNE, prepare = PrepareOutcome.NEVER_RETURNS))
            val session = graphPlayback(koin)()
            try {
                session.onPlayBook(BookId(BOOK_ID), null)

                // Read before anything could suspend: the prime ran inside the call, which is what Safari needs.
                koin.get<HtmlAudioPlayer>().state.value shouldBe PlaybackState.Buffering
                // And the bar names the book it is preparing, before the prepare answers (§3.6).
                val preparing = withTimeout(TIMEOUT_MS) { session.state.first { it is PlayerUiState.Loaded } }
                preparing.shouldBeInstanceOf<PlayerUiState.Loaded>().phase shouldBe PlayerPhase.Preparing
            } finally {
                session.onCloseBook()
                session.close()
                URL.revokeObjectURL(segment.url)
            }
        }

        test("the Play control drives a real prepare all the way to audible playback") {
            val segment = silentSegment(SEGMENT_MS)
            val koin = bootBrowserGraph(fakePlaybackManager(segment, title = DUNE))
            val player = koin.get<HtmlAudioPlayer>()
            val session = graphPlayback(koin)()
            try {
                session.onPlayBook(BookId(BOOK_ID), null)

                player.awaitState(PlaybackState.Playing)
                // Not merely "Playing was reported": the element's own clock has to have moved.
                withTimeout(TIMEOUT_MS) { player.positionMs.first { it > 0L } } shouldBeGreaterThan 0L
                val playing =
                    withTimeout(TIMEOUT_MS) {
                        session.state.first { (it as? PlayerUiState.Loaded)?.phase == PlayerPhase.Playing }
                    }
                playing.shouldBeInstanceOf<PlayerUiState.Loaded>().book.title shouldBe DUNE
            } finally {
                session.onCloseBook()
                session.close()
                URL.revokeObjectURL(segment.url)
            }
        }

        test("Close book records the pause, lets go of the element and shows nothing, and the next Play works") {
            val segment = silentSegment(SEGMENT_MS)
            val manager = fakePlaybackManager(segment, title = DUNE)
            val koin = bootBrowserGraph(manager)
            val player = koin.get<HtmlAudioPlayer>()
            val session = graphPlayback(koin)()
            try {
                session.onPlayBook(BookId(BOOK_ID), null)
                withTimeout(TIMEOUT_MS) { manager.playing.first { it } }

                session.onCloseBook()

                // Filed under the book while it was still the current one: on web only the manager persists a pause.
                manager.recordedStates shouldBe listOf(BookId(BOOK_ID) to PlaybackState.Paused)
                player.state.value shouldBe PlaybackState.Idle
                player.isPaused shouldBe true
                withTimeout(TIMEOUT_MS) { session.state.first { it == PlayerUiState.NothingLoaded } }

                // Closing a book is not a sign-out: the same session plays the next one.
                session.onPlayBook(BookId(BOOK_ID), null)
                player.awaitState(PlaybackState.Playing)
            } finally {
                session.onCloseBook()
                session.close()
                URL.revokeObjectURL(segment.url)
            }
        }

        test("switching books pauses the playing one at the tap without spending the tap: the next book plays") {
            // The engine pauses the outgoing book before it primes. A pause after the prime would cancel the tap's
            // standing "play what loads next", and the new book would sit silently on Paused.
            val segment = silentSegment(SEGMENT_MS)
            val manager = fakePlaybackManager(segment, title = DUNE)
            val koin = bootBrowserGraph(manager)
            val player = koin.get<HtmlAudioPlayer>()
            val session = graphPlayback(koin)()
            try {
                session.onPlayBook(BookId(BOOK_ID), null)
                // The manager's flag, not the element's state: it is what the engine reads to decide on a pause.
                withTimeout(TIMEOUT_MS) { manager.playing.first { it } }

                session.onPlayBook(BookId(OTHER_BOOK_ID), null)

                // Read before anything could suspend: the prime survived the pause that preceded it.
                player.state.value shouldBe PlaybackState.Buffering
                withTimeout(TIMEOUT_MS) { manager.currentBookId.first { it == BookId(OTHER_BOOK_ID) } }
                player.awaitState(PlaybackState.Playing)
            } finally {
                session.onCloseBook()
                session.close()
                URL.revokeObjectURL(segment.url)
            }
        }

        test("closing the session (sign-out) stops the audio it owns") {
            // Sign-out unmounts the shell, which closes the session. Without the release the book would narrate
            // on over the login screen with no bar left to stop it.
            val segment = silentSegment(SEGMENT_MS)
            val koin = bootBrowserGraph(fakePlaybackManager(segment, title = DUNE))
            val player = koin.get<HtmlAudioPlayer>()
            val session = graphPlayback(koin)()
            session.onPlayBook(BookId(BOOK_ID), null)
            player.awaitState(PlaybackState.Playing)

            session.close()

            player.state.value shouldBe PlaybackState.Idle
            player.isPaused shouldBe true
            URL.revokeObjectURL(segment.url)
        }

        test("closing the session mid-prepare withdraws the primed intent") {
            // A prime is a standing "play the next thing you load" on a singleton element. A sign-out while the
            // prepare is in flight must take it back, or the next load plays on the strength of a dead tap.
            val pending = silentSegment(SEGMENT_MS)
            val koin = bootBrowserGraph(fakePlaybackManager(pending, title = DUNE, prepare = PrepareOutcome.NEVER_RETURNS))
            val player = koin.get<HtmlAudioPlayer>()
            val session = graphPlayback(koin)()
            session.onPlayBook(BookId(BOOK_ID), null)

            session.close()
            val unrelated = silentSegment(SEGMENT_MS)
            player.load(listOf(unrelated))

            player.awaitState(PlaybackState.Paused)
            URL.revokeObjectURL(pending.url)
            URL.revokeObjectURL(unrelated.url)
        }

        test("a boost reaches the player as real gain, not just as a stored number") {
            // The assertion is on the PLAYER: the manager recomputes its number whether or not anything listens,
            // so only the element's applied gain proves the session's gain collector is wired.
            val segment = silentSegment(SEGMENT_MS)
            val manager = fakePlaybackManager(segment, title = DUNE)
            val koin = bootBrowserGraph(manager)
            val player = koin.get<HtmlAudioPlayer>()
            val session = graphPlayback(koin)()
            try {
                session.onPlayBook(BookId(BOOK_ID), null)
                player.awaitState(PlaybackState.Playing)
                withTimeout(TIMEOUT_MS) { session.state.first { it is PlayerUiState.Loaded } }

                session.onSetBoost(BOOSTED_DB)

                manager.volumeBoostDb.value shouldBe BOOSTED_DB
                player.awaitAppliedGain(BOOSTED_LINEAR)
            } finally {
                session.onCloseBook()
                session.close()
                URL.revokeObjectURL(segment.url)
            }
        }

        test("a boost stored on the book survives arriving before the audio does") {
            // The manager publishes the gain inside the prepare, before the element has a source, and a gain stage
            // cannot route an element with nothing loaded. The collector re-applies it once the audio arrives.
            val segment = silentSegment(SEGMENT_MS)
            val koin = bootBrowserGraph(fakePlaybackManager(segment, title = DUNE, resumeBoostDb = BOOSTED_DB))
            val player = koin.get<HtmlAudioPlayer>()
            val session = graphPlayback(koin)()
            try {
                session.onPlayBook(BookId(BOOK_ID), null)
                player.awaitState(PlaybackState.Playing)

                player.awaitAppliedGain(BOOSTED_LINEAR)
                player.boostAttached shouldBe true
                player.boostUnavailable.value shouldBe false
            } finally {
                session.onCloseBook()
                session.close()
                URL.revokeObjectURL(segment.url)
            }
        }
    })

/**
 * The browser graph as `Main.kt` boots it, with the prepare and the book's Room row stood in for.
 * [startWebKoin] builds the eager singletons, so the shared `PlayerSession` exists from the start.
 */
private fun bootBrowserGraph(manager: FakePlaybackManager): Koin =
    startWebKoin(
        module { single<Worker> { createSqliteWorker() } },
        module {
            single<PlaybackManager> { manager }
            single<BookRepository> {
                FakeBookRepository(
                    mapOf(BOOK_ID to duneDetail(), OTHER_BOOK_ID to duneDetail().copy(id = BookId(OTHER_BOOK_ID))),
                )
            }
        },
    ).koin

private fun duneDetail(): BookDetail =
    BookDetail(
        id = BookId(BOOK_ID),
        libraryId = LibraryId("library-1"),
        folderId = FolderId("folder-1"),
        title = DUNE,
        authors = listOf(BookContributor(id = "c1", name = "Frank Herbert")),
        narrators = emptyList(),
        duration = SEGMENT_MS,
        coverPath = null,
        addedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )

/** Polled: the multiplier is computed on read (gain × fade), not published as a flow. */
private suspend fun HtmlAudioPlayer.awaitAppliedGain(target: Double) {
    withTimeout(TIMEOUT_MS) {
        while (abs(appliedGain - target) > GAIN_TOLERANCE) delay(GAIN_POLL_MS)
    }
}

private const val BOOK_ID = "book-1"

private const val OTHER_BOOK_ID = "book-2"

private const val DUNE = "Dune"

/** Long enough for the element to run a measurable clock, short enough to keep the lane quick. */
private const val SEGMENT_MS = 1_500L

private const val TIMEOUT_MS = 15_000L

private const val BOOSTED_DB = 6f

/** The linear form of [BOOSTED_DB]: what an amplifying stage actually multiplies by. */
private const val BOOSTED_LINEAR = 1.9952624

private const val GAIN_TOLERANCE = 0.001

private const val GAIN_POLL_MS = 10L
