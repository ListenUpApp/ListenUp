package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.nowplaying.NowPlayingBook
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.nowplaying.PlayerLink
import com.calypsan.listenup.web.features.nowplaying.PlayerSeriesLink
import com.calypsan.listenup.web.features.nowplaying.TransportChapter
import com.calypsan.listenup.web.features.nowplaying.TransportState
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

private fun transportState(
    title: String,
    isPlaying: Boolean,
): TransportState =
    TransportState(
        title = title,
        isPlaying = isPlaying,
        positionMs = POSITION_MS,
        durationMs = DURATION_MS,
    )

private fun dune(): NowPlayingBook =
    NowPlayingBook(
        bookId = FIRST_BOOK_ID,
        coverHash = FIRST_COVER_HASH,
        authors = listOf(PlayerLink("c1", "Frank Herbert"), PlayerLink("c2", "Brian Herbert")),
        narrators = "Simon Vance",
        series = listOf(PlayerSeriesLink("s1", FIRST_SERIES, "1")),
    )

private fun piranesi(): NowPlayingBook =
    NowPlayingBook(
        bookId = SECOND_BOOK_ID,
        coverHash = SECOND_COVER_HASH,
        authors = listOf(PlayerLink("c3", SECOND_ARTIST)),
        narrators = "Chiwetel Ejiofor",
        series = listOf(PlayerSeriesLink("s2", SECOND_SERIES, null)),
    )

/**
 * A session over the two flows this binding reads, and one recordable gesture.
 *
 * Constructed rather than borrowed from `fixedPlayback`, whose flows cannot be driven — two of
 * these specs are about what happens when the playing book *changes*.
 */
private fun session(
    state: StateFlow<TransportState?>,
    nowPlaying: StateFlow<NowPlayingBook?>,
    onPlayPause: () -> Unit,
): PlaybackSession =
    PlaybackSession(
        state = state,
        error = MutableStateFlow<String?>(null),
        chapters = MutableStateFlow<List<TransportChapter>>(emptyList()),
        currentChapterIndex = MutableStateFlow<Int?>(null),
        nowPlaying = nowPlaying,
        sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive),
        defaultSpeed = MutableStateFlow(1.0f),
        volumeBoostDb = MutableStateFlow(0f),
        defaultBoostDb = MutableStateFlow(0f),
        boostUnavailable = MutableStateFlow(false),
        onPlayPause = onPlayPause,
        onSeek = {},
        onPlayBook = {},
        onSkipBack = {},
        onSkipForward = {},
        onSetSpeed = {},
        onResetSpeed = {},
        onSetBoost = {},
        onResetBoost = {},
        onSeekToChapter = {},
        onSetSleepTimer = {},
        onCancelSleepTimer = {},
        onExtendSleepTimer = {},
        onDismissError = {},
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
                            state = MutableStateFlow<TransportState?>(transportState(FIRST_TITLE, isPlaying = true)),
                            nowPlaying = MutableStateFlow<NowPlayingBook?>(dune()),
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
            val state = MutableStateFlow<TransportState?>(transportState(FIRST_TITLE, isPlaying = true))
            val nowPlaying = MutableStateFlow<NowPlayingBook?>(dune())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session = session(state = state, nowPlaying = nowPlaying, onPlayPause = {}),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.metadata.isNotEmpty() }
                bridge.metadata.first().title shouldBe FIRST_TITLE

                nowPlaying.value = piranesi()
                state.value = transportState(SECOND_TITLE, isPlaying = true)

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
            val state = MutableStateFlow<TransportState?>(transportState(FIRST_TITLE, isPlaying = true))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dispose =
                bindMediaSession(
                    session =
                        session(
                            state = state,
                            nowPlaying = MutableStateFlow<NowPlayingBook?>(dune()),
                            onPlayPause = {},
                        ),
                    bridge = bridge,
                    scope = scope,
                )
            try {
                awaitTrue { bridge.playing == listOf(true) }

                state.value = transportState(FIRST_TITLE, isPlaying = false)

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
                            state = MutableStateFlow<TransportState?>(transportState(FIRST_TITLE, isPlaying = true)),
                            nowPlaying = MutableStateFlow<NowPlayingBook?>(dune()),
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
                            state = MutableStateFlow<TransportState?>(transportState(FIRST_TITLE, isPlaying = true)),
                            nowPlaying = MutableStateFlow<NowPlayingBook?>(dune()),
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
