package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.nowplaying.chapterRows
import com.calypsan.listenup.web.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.web.features.nowplaying.playerProgress
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import com.calypsan.listenup.client.domain.model.LoadedBook
import kotlinx.coroutines.withTimeout

/** How long a spec waits for a mirror that SHOULD reach the bridge. */
private const val EVENT_TIMEOUT_MS = 2_000L

/** Poll interval while waiting for a recorded call. */
private const val POLL_MS = 10L

private const val FIRST_TITLE = "Dune"
private const val FIRST_BOOK_ID = "book-1"
private const val FIRST_COVER_HASH = "hash-1"
private const val FIRST_SERIES = "Dune Chronicles"
private const val FIRST_AUTHORS = "Frank Herbert, Brian Herbert"

private const val SECOND_TITLE = "Piranesi"
private const val SECOND_BOOK_ID = "book-2"
private const val SECOND_COVER_HASH = "hash-2"
private const val SECOND_SERIES = "Standalones"
private const val SECOND_AUTHOR = "Susanna Clarke"

private const val POSITION_MS = 60_000L
private const val DURATION_MS = 600_000L

/** Every action the OS controls can send that this app answers. */
private val TRANSPORT_ACTIONS =
    setOf("play", "pause", "stop", "seekbackward", "seekforward", "previoustrack", "nexttrack")

private data class RecordedMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
)

/**
 * A [MediaSessionBridge] that remembers everything said to it.
 *
 * `navigator.mediaSession.setActionHandler` has no getter, so recording the registration is the
 * only way a spec can prove a handler was installed at all.
 */
private class RecordingMediaSession : MediaSessionBridge {
    val metadata = mutableListOf<RecordedMetadata>()
    val playing = mutableListOf<Boolean>()
    val positions = mutableListOf<Triple<Long, Long, Float>>()
    val handlers = mutableMapOf<String, (() -> Unit)?>()
    var seekTo: ((Long) -> Unit)? = null
    var clears = 0

    override fun clear() {
        clears++
    }

    override fun setMetadata(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
    ) {
        metadata += RecordedMetadata(title, artist, album, artworkUrl)
    }

    override fun setPlaying(playing: Boolean) {
        this.playing += playing
    }

    override fun setPosition(
        durationMs: Long,
        positionMs: Long,
        speed: Float,
    ) {
        positions += Triple(durationMs, positionMs, speed)
    }

    override fun setHandler(
        action: String,
        handler: (() -> Unit)?,
    ) {
        handlers[action] = handler
    }

    override fun setSeekToHandler(handler: ((Long) -> Unit)?) {
        seekTo = handler
    }
}

/**
 * Dune by both Herberts, read by Simon Vance, book 1 of the Chronicles; playing unless [phase] says not. Three
 * chapters of 200 s, the second current unless [chapter] says otherwise.
 */
private fun dune(
    phase: PlayerPhase = PlayerPhase.Playing,
    chapter: Int = 1,
    titles: List<String> = listOf("Prologue", "The Desert", "Arrakis"),
): PlayerUiState =
    loadedPlayerState(
        title = FIRST_TITLE,
        bookId = FIRST_BOOK_ID,
        phase = phase,
        chapters = chapterRows(3, spacingMs = 200_000L).mapIndexed { i, row -> row.copy(title = titles[i]) },
        currentChapterIndex = chapter,
        authors = listOf(PlayerContributor("c1", "Frank Herbert", 1), PlayerContributor("c2", "Brian Herbert", 1)),
        narrators = listOf(PlayerContributor("n1", "Simon Vance", 1)),
        series = listOf(PlayerSeriesRef("s1", FIRST_SERIES, "1")),
        coverHash = FIRST_COVER_HASH,
        durationMs = DURATION_MS,
    )

private fun piranesi(): PlayerUiState =
    loadedPlayerState(
        title = SECOND_TITLE,
        bookId = SECOND_BOOK_ID,
        authors = listOf(PlayerContributor("c3", SECOND_AUTHOR, 1)),
        narrators = listOf(PlayerContributor("n2", "Chiwetel Ejiofor", 1)),
        series = listOf(PlayerSeriesRef("s2", SECOND_SERIES, null)),
        coverHash = SECOND_COVER_HASH,
        durationMs = DURATION_MS,
    )

/**
 * A session over the flow this binding reads, and one recordable gesture.
 *
 * Constructed rather than borrowed from `fixedPlayback`, whose flows cannot be driven — two of
 * these specs are about what happens when the loaded book *changes*.
 */
private fun session(
    state: StateFlow<PlayerUiState>,
    onPlayPause: () -> Unit,
    progress: StateFlow<PlayerProgress> =
        MutableStateFlow(playerProgress(positionMs = POSITION_MS, durationMs = DURATION_MS)),
    calls: MutableList<String> = mutableListOf(),
): PlaybackSession =
    PlaybackSession(
        state = state,
        progress = progress,
        loadedBook = flowOf(LoadedBook.NothingLoaded),
        overlay = MutableStateFlow(PlayerOverlay.None),
        preparingBookId = MutableStateFlow<String?>(null),
        boostUnavailable = MutableStateFlow(false),
        events = emptyFlow(),
        onPrimary = {},
        onPlayPause = onPlayPause,
        onPlayBook = { _, _ -> },
        onSeek = { calls += "seek $it" },
        onSkipBack = { calls += "skip back" },
        onSkipForward = { calls += "skip forward" },
        onPreviousChapter = { calls += "previous chapter" },
        onNextChapter = { calls += "next chapter" },
        onSeekToChapter = {},
        onSetSpeed = {},
        onResetSpeed = {},
        onStepSpeed = {},
        onSetBoost = {},
        onResetBoost = {},
        onSetSleepTimer = {},
        onCancelSleepTimer = {},
        onExtendSleepTimer = {},
        onRetry = {},
        onDismissError = {},
        onCloseBook = {},
        onCardAction = {},
        onRate = {},
        close = {},
    )

private suspend fun awaitTrue(condition: () -> Boolean) {
    withTimeout(EVENT_TIMEOUT_MS) { while (!condition()) delay(POLL_MS) }
}

/**
 * What the OS media controls are told, and what they are allowed to do back.
 *
 * The `<audio>` element is never in the document, so a media key, a lock screen and a Bluetooth
 * button reach this page only through `navigator.mediaSession`. These specs drive the binding
 * over a recording bridge, and one of them drives the real browser API to prove the bridge under
 * it is not a fiction.
 */
class MediaSessionBindingTest :
    FunSpec({

        test("the playing book's identity reaches the OS controls") {
            val bridge = RecordingMediaSession()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session =
                        session(
                            state = MutableStateFlow(dune()),
                            onPlayPause = {},
                        ),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.metadata.isNotEmpty() }

                // The book, then the chapter as the book names it, by the authors (spec §5.7).
                bridge.metadata.first() shouldBe
                    RecordedMetadata(
                        title = FIRST_TITLE,
                        artist = "The Desert",
                        album = FIRST_AUTHORS,
                        artworkUrl = coverUrl(FIRST_BOOK_ID, FIRST_COVER_HASH, MEDIA_SESSION_ARTWORK_PX),
                    )
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a book change replaces the metadata") {
            val bridge = RecordingMediaSession()
            val state = MutableStateFlow(dune())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = {}),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.metadata.isNotEmpty() }
                bridge.metadata.first().title shouldBe FIRST_TITLE

                state.value = piranesi()

                // The arrival of the SECOND book's own metadata is the assertion; a timeout here
                // means the binding kept telling the OS about a book that stopped playing.
                awaitTrue {
                    bridge.metadata.any {
                        it ==
                            RecordedMetadata(
                                title = SECOND_TITLE,
                                artist = "Whole book",
                                album = SECOND_AUTHOR,
                                artworkUrl = coverUrl(SECOND_BOOK_ID, SECOND_COVER_HASH, MEDIA_SESSION_ARTWORK_PX),
                            )
                    }
                }
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("pausing is reflected in the OS controls") {
            val bridge = RecordingMediaSession()
            val state = MutableStateFlow(dune())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = {}),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.playing == listOf(true) }

                state.value = dune(phase = PlayerPhase.Paused)

                awaitTrue { bridge.playing == listOf(true, false) }
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("every transport action is registered") {
            val bridge = RecordingMediaSession()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session =
                        session(
                            state = MutableStateFlow(dune()),
                            onPlayPause = {},
                        ),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                // Next track follows the chapter, so it arrives with the state rather than at once.
                awaitTrue { bridge.handlers["nexttrack"] != null }
                bridge.handlers.keys shouldBe TRANSPORT_ACTIONS
                bridge.handlers.values.forEach { it shouldNotBe null }
                bridge.seekTo shouldNotBe null
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("play starts a paused book, and pause stops a playing one") {
            val bridge = RecordingMediaSession()
            var playPauses = 0
            val state = MutableStateFlow(dune(phase = PlayerPhase.Paused))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = { playPauses++ }),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                // The handlers arrive with the loaded book, not at bind time.
                awaitTrue { bridge.handlers["play"] != null }
                bridge.handlers.getValue("play")!!.invoke()
                playPauses shouldBe 1

                state.value = dune(phase = PlayerPhase.Playing)
                bridge.handlers.getValue("pause")!!.invoke()
                playPauses shouldBe 2
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("play while playing and pause while paused do nothing: they are not toggles") {
            val bridge = RecordingMediaSession()
            var playPauses = 0
            val state = MutableStateFlow(dune(phase = PlayerPhase.Playing))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = { playPauses++ }),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                // The handlers arrive with the loaded book, not at bind time.
                awaitTrue { bridge.handlers["play"] != null }
                bridge.handlers.getValue("play")!!.invoke()
                state.value = dune(phase = PlayerPhase.Paused)
                bridge.handlers.getValue("pause")!!.invoke()
                bridge.handlers.getValue("stop")!!.invoke()

                playPauses shouldBe 0
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("stop pauses a playing book") {
            val bridge = RecordingMediaSession()
            var playPauses = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = MutableStateFlow(dune()), onPlayPause = { playPauses++ }),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.handlers["stop"] != null }
                bridge.handlers.getValue("stop")!!.invoke()

                playPauses shouldBe 1
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("previous and next track move by chapter; the seek buttons skip") {
            val bridge = RecordingMediaSession()
            val calls = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = MutableStateFlow(dune()), onPlayPause = {}, calls = calls),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.handlers["nexttrack"] != null }
                bridge.handlers.getValue("previoustrack")!!.invoke()
                bridge.handlers.getValue("nexttrack")!!.invoke()
                bridge.handlers.getValue("seekbackward")!!.invoke()
                bridge.handlers.getValue("seekforward")!!.invoke()

                calls shouldBe listOf("previous chapter", "next chapter", "skip back", "skip forward")
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("at the last chapter next track is removed, and comes back when the book moves earlier") {
            val bridge = RecordingMediaSession()
            val state = MutableStateFlow(dune(chapter = 2))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = {}),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { "nexttrack" in bridge.handlers && bridge.handlers["nexttrack"] == null }

                state.value = dune(chapter = 0)

                awaitTrue { bridge.handlers["nexttrack"] != null }
                bridge.handlers["previoustrack"] shouldNotBe null
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("the position is the chapter's, and a seek lands in that chapter") {
            val bridge = RecordingMediaSession()
            val calls = mutableListOf<String>()
            val progress =
                MutableStateFlow(
                    PlayerProgress(
                        chapterPositionMs = 50_000L,
                        chapterDurationMs = 200_000L,
                        bookPositionMs = 250_000L,
                        bookDurationMs = DURATION_MS,
                    ),
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session =
                        session(state = MutableStateFlow(dune()), onPlayPause = {}, progress = progress, calls = calls),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.positions.isNotEmpty() }
                bridge.positions.last() shouldBe Triple(200_000L, 50_000L, 1f)

                bridge.seekTo!!.invoke(30_000L)

                // 30 s into "The Desert", which starts 200 s into the book.
                calls shouldBe listOf("seek 230000")
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("a chapter change re-names the track; an untitled chapter is named by its number") {
            val bridge = RecordingMediaSession()
            val state = MutableStateFlow(dune(chapter = 1))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = {}),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.metadata.isNotEmpty() }

                state.value = dune(chapter = 2, titles = listOf("Prologue", "The Desert", " "))

                awaitTrue { bridge.metadata.any { it.artist == "Chapter 3" } }
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("closing the book clears the OS controls") {
            val bridge = RecordingMediaSession()
            val state = MutableStateFlow<PlayerUiState>(dune())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = {}),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.metadata.isNotEmpty() }

                state.value = PlayerUiState.NothingLoaded

                awaitTrue { bridge.clears > 0 }
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("closing the book unregisters every action, so a media key with nothing loaded does nothing") {
            val bridge = RecordingMediaSession()
            val calls = mutableListOf<String>()
            var playPauses = 0
            val state = MutableStateFlow<PlayerUiState>(dune(phase = PlayerPhase.Paused))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, onPlayPause = { playPauses++ }, calls = calls),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.handlers["nexttrack"] != null }

                state.value = PlayerUiState.NothingLoaded

                // A closed book answers nothing: the OS is told there is no handler, not handed one that acts anyway.
                awaitTrue { TRANSPORT_ACTIONS.all { it in bridge.handlers && bridge.handlers[it] == null } }
                bridge.seekTo shouldBe null
                playPauses shouldBe 0
                calls shouldBe emptyList()

                // The next book brings them back.
                state.value = dune(phase = PlayerPhase.Paused)
                awaitTrue { bridge.handlers["play"] != null && bridge.handlers["previoustrack"] != null }
                bridge.seekTo shouldNotBe null
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("the browser's clear also drops the position state, so no stale scrubber is left behind") {
            val real = browserMediaSession()
            real shouldNotBe null
            js(
                "window.__luPositionStateCalls = [];" +
                    "navigator.mediaSession.setPositionState = function () {" +
                    " window.__luPositionStateCalls.push(arguments.length); };",
            )
            try {
                real!!.clear()

                js("window.__luPositionStateCalls").unsafeCast<Array<Int>>().toList() shouldBe listOf(0)
            } finally {
                // The own property shadowed the prototype's method; deleting it restores the real one.
                js("delete navigator.mediaSession.setPositionState; delete window.__luPositionStateCalls;")
            }
        }

        test("Chromium exposes a real media session and accepts our metadata") {
            // Not a skip when the detection says no: Chromium HAS Media Session, so a null here
            // means `browserMediaSession` is wrong and the whole feature is silently off.
            val real = browserMediaSession()
            real shouldNotBe null

            real!!.setMetadata(title = FIRST_TITLE, artist = "Herbert", album = FIRST_SERIES, artworkUrl = null)

            js("navigator.mediaSession.metadata.title").toString() shouldBe FIRST_TITLE
            js("navigator.mediaSession.metadata.artist").toString() shouldBe "Herbert"
        }
    })
