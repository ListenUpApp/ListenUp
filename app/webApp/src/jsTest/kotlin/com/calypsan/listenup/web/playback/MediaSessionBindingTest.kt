package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
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
private const val FIRST_ARTIST = "Frank Herbert, Brian Herbert"

private const val SECOND_TITLE = "Piranesi"
private const val SECOND_BOOK_ID = "book-2"
private const val SECOND_COVER_HASH = "hash-2"
private const val SECOND_SERIES = "Standalones"
private const val SECOND_ARTIST = "Susanna Clarke"

private const val POSITION_MS = 60_000L
private const val DURATION_MS = 600_000L

/** Every action the OS controls can send that this app answers. */
private val TRANSPORT_ACTIONS =
    setOf("play", "pause", "seekbackward", "seekforward", "previoustrack", "nexttrack")

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

/** Dune by both Herberts, read by Simon Vance, book 1 of the Chronicles; playing unless [phase] says not. */
private fun dune(phase: PlayerPhase = PlayerPhase.Playing): PlayerUiState =
    loadedPlayerState(
        title = FIRST_TITLE,
        bookId = FIRST_BOOK_ID,
        phase = phase,
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
        authors = listOf(PlayerContributor("c3", SECOND_ARTIST, 1)),
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
): PlaybackSession =
    PlaybackSession(
        state = state,
        progress = MutableStateFlow(playerProgress(positionMs = POSITION_MS, durationMs = DURATION_MS)),
        loadedBook = flowOf(LoadedBook.NothingLoaded),
        overlay = MutableStateFlow(PlayerOverlay.None),
        preparingBookId = MutableStateFlow<String?>(null),
        boostUnavailable = MutableStateFlow(false),
        events = emptyFlow(),
        onPrimary = {},
        onPlayPause = onPlayPause,
        onPlayBook = { _, _ -> },
        onSeek = {},
        onSkipBack = {},
        onSkipForward = {},
        onPreviousChapter = {},
        onNextChapter = {},
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

                bridge.metadata.first() shouldBe
                    RecordedMetadata(
                        title = FIRST_TITLE,
                        artist = FIRST_ARTIST,
                        album = FIRST_SERIES,
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
                                artist = SECOND_ARTIST,
                                album = SECOND_SERIES,
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
                bridge.handlers.keys shouldBe TRANSPORT_ACTIONS
                bridge.handlers.values.forEach { it shouldNotBe null }
                bridge.seekTo shouldNotBe null
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("invoking the play handler reaches the session's callback") {
            val bridge = RecordingMediaSession()
            var playPauses = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session =
                        session(
                            state = MutableStateFlow(dune()),
                            onPlayPause = { playPauses++ },
                        ),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                bridge.handlers.getValue("play")!!.invoke()

                playPauses shouldBe 1
            } finally {
                dispose()
                scope.cancel()
            }
        }

        test("Chromium exposes a real media session and accepts our metadata") {
            // Not a skip when the detection says no: Chromium HAS Media Session, so a null here
            // means `browserMediaSession` is wrong and the whole feature is silently off.
            val real = browserMediaSession()
            real shouldNotBe null

            real!!.setMetadata(title = FIRST_TITLE, artist = "Herbert", album = FIRST_SERIES, artworkUrl = null)

            js("navigator.mediaSession.metadata.title").toString() shouldBe FIRST_TITLE
        }
    })
