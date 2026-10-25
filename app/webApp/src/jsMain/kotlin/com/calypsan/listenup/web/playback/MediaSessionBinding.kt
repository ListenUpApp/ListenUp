package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.bookPositionInChapterMs
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.nowplaying.surfaceChapterName
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The slice of `navigator.mediaSession` this app uses.
 *
 * An interface rather than direct calls so the binding above it can be driven by a spec:
 * `setActionHandler` has no getter, so a test can only prove a handler was registered by
 * recording the registration.
 */
internal interface MediaSessionBridge {
    fun setMetadata(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
    )

    fun setPlaying(playing: Boolean)

    fun setPosition(
        durationMs: Long,
        positionMs: Long,
        speed: Float,
    )

    fun setHandler(
        action: String,
        handler: (() -> Unit)?,
    )

    fun setSeekToHandler(handler: ((Long) -> Unit)?)

    /** Nothing is loaded: no metadata, and a state of "none", so the OS stops offering a closed book. */
    fun clear()
}

/**
 * `MediaMetadata` is a global constructor taking an object literal. Declared `external` rather
 * than written as a `js("new MediaMetadata(...)")` block because a `js()` body cannot capture
 * Kotlin locals — the object it needs is built here.
 */
private external class MediaMetadata(
    init: dynamic,
)

/**
 * The real `navigator.mediaSession`, or null when this browser has none.
 *
 * Feature-detected rather than assumed: Media Session is unavailable in several browsers this
 * app is expected to run in, and "never stranded" means the transport bar keeps working when
 * the OS controls cannot.
 */
internal fun browserMediaSession(): MediaSessionBridge? =
    if (js("typeof navigator !== 'undefined' && typeof navigator.mediaSession === 'object'").unsafeCast<Boolean>()) {
        BrowserMediaSession()
    } else {
        null
    }

private class BrowserMediaSession : MediaSessionBridge {
    private val session: dynamic get() = window.navigator.asDynamic().mediaSession

    override fun setMetadata(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String?,
    ) {
        val init = js("({})")
        init.title = title
        init.artist = artist
        init.album = album
        init.artwork =
            if (artworkUrl == null) {
                emptyArray<Any>()
            } else {
                val art = js("({})")
                art.src = artworkUrl
                art.sizes = ARTWORK_SIZES
                arrayOf(art)
            }
        session.metadata = MediaMetadata(init)
    }

    override fun setPlaying(playing: Boolean) {
        session.playbackState = if (playing) "playing" else "paused"
    }

    override fun setPosition(
        durationMs: Long,
        positionMs: Long,
        speed: Float,
    ) {
        // The browser throws on a position past the duration or a duration of zero, and a book
        // that has not finished loading reports exactly that.
        if (durationMs <= 0L || positionMs < 0L || positionMs > durationMs) return
        val state = js("({})")
        state.duration = durationMs / MS_PER_SECOND
        state.position = positionMs / MS_PER_SECOND
        state.playbackRate = speed.toDouble()
        session.setPositionState(state)
    }

    override fun setHandler(
        action: String,
        handler: (() -> Unit)?,
    ) {
        session.setActionHandler(action, handler)
    }

    override fun setSeekToHandler(handler: ((Long) -> Unit)?) {
        val bridged =
            handler?.let { seek ->
                { event: dynamic -> seek((event.seekTime.unsafeCast<Double>() * MS_PER_SECOND).toLong()) }
            }
        session.setActionHandler("seekto", bridged)
    }

    override fun clear() {
        session.metadata = null
        session.playbackState = "none"
    }
}

/** Artwork rung asked of the cover endpoint — big enough for a lock screen. */
internal const val MEDIA_SESSION_ARTWORK_PX = 512

/**
 * Mirrors [session] into the OS media controls, and returns the disposer.
 *
 * The `<audio>` element is never in the document ([HtmlAudioPlayer]), so the browser has no UI
 * to attach to on its own — a media key, a lock screen and a Bluetooth button all reach the
 * page only through this API. Like the Android card and the iOS lock screen (spec §5.7), the OS
 * sees the chapter as the track: title = the book, artist = the chapter, album = the author, a
 * chapter-scoped position, and previous / next track = previous / next chapter by the shared rule
 * (§3.3), with next track removed at the last chapter (a `null` handler hides the button). The
 * metadata changes once per chapter and the position on every tick, so the two are collected
 * separately and `distinctUntilChanged` keeps the browser from being rewritten a few times a
 * second. Closing the book clears it all.
 */
internal fun bindMediaSession(
    session: PlaybackSession,
    bridge: MediaSessionBridge,
    scope: CoroutineScope,
): () -> Unit {
    val loaded = session.state.map { it as? PlayerUiState.Loaded }

    fun isGoing(): Boolean = (session.state.value as? PlayerUiState.Loaded)?.phase?.isGoing() == true

    // Not toggles: a headset that repeats "pause", or a browser that sends it while we are already paused, must
    // not start the book.
    bridge.setHandler("play") { if (!isGoing()) session.onPlayPause() }
    bridge.setHandler("pause") { if (isGoing()) session.onPlayPause() }
    bridge.setHandler("stop") { if (isGoing()) session.onPlayPause() }
    bridge.setHandler("seekbackward", session.onSkipBack)
    bridge.setHandler("seekforward", session.onSkipForward)
    bridge.setHandler("previoustrack", session.onPreviousChapter)
    bridge.setSeekToHandler { offsetMs ->
        (session.state.value as? PlayerUiState.Loaded)?.let {
            session.onSeek(
                bookPositionInChapterMs(it.chapter, offsetMs),
            )
        }
    }

    val job =
        scope.launch {
            launch {
                loaded
                    .map { state -> state?.let(::TrackInfo) }
                    .distinctUntilChanged()
                    .collect { track -> if (track == null) bridge.clear() else bridge.publish(track) }
            }
            launch {
                loaded
                    .map { it?.canSkipToNextChapter == true }
                    .distinctUntilChanged()
                    .collect { hasNext -> bridge.setHandler("nexttrack", session.onNextChapter.takeIf { hasNext }) }
            }
            launch {
                loaded
                    .map { state ->
                        state?.let { it.phase == PlayerPhase.Playing || it.phase == PlayerPhase.SleepFading }
                    }.distinctUntilChanged()
                    .collect { playing -> playing?.let(bridge::setPlaying) }
            }
            launch {
                combine(loaded, session.progress) { state, progress -> state?.let { it to progress } }
                    .collect { pair ->
                        pair?.let { (state, progress) ->
                            bridge.setPosition(
                                progress.chapterDurationMs,
                                progress.chapterPositionMs,
                                state.settings.speed,
                            )
                        }
                    }
            }
        }

    return {
        job.cancel()
        TRANSPORT_ACTIONS.forEach { bridge.setHandler(it, null) }
        bridge.setSeekToHandler(null)
    }
}

/** Playing, or about to be: what a "pause" stops and a "play" leaves alone. */
private fun PlayerPhase.isGoing(): Boolean =
    this == PlayerPhase.Playing ||
        this == PlayerPhase.SleepFading ||
        this == PlayerPhase.Preparing ||
        this == PlayerPhase.Buffering ||
        this == PlayerPhase.Reconnecting

/** What the OS is told is playing: changes once per chapter, never on a tick. */
private data class TrackInfo(
    val book: String,
    val chapter: String,
    val authors: String,
    val artworkUrl: String,
) {
    constructor(state: PlayerUiState.Loaded) : this(
        book = state.book.title,
        chapter = surfaceChapterName(state.chapter),
        authors = state.book.authors.joinToString(", ") { it.name },
        artworkUrl = coverUrl(state.book.id, state.book.coverHash, MEDIA_SESSION_ARTWORK_PX),
    )
}

/** Tells the OS what is playing: the book, the chapter as the book names it, who wrote it, its cover. */
private fun MediaSessionBridge.publish(track: TrackInfo) {
    setMetadata(title = track.book, artist = track.chapter, album = track.authors, artworkUrl = track.artworkUrl)
}

/** Every action the OS controls can send that this app answers — registered and cleared together. */
private val TRANSPORT_ACTIONS =
    listOf("play", "pause", "stop", "seekbackward", "seekforward", "previoustrack", "nexttrack")

/** The one artwork rung declared to the OS — the lock-screen size the cover endpoint can serve. */
private const val ARTWORK_SIZES = "512x512"

private const val MS_PER_SECOND = 1000.0
