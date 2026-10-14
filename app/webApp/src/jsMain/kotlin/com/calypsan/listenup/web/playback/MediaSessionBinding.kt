package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
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
}

/** Artwork rung asked of the cover endpoint — big enough for a lock screen. */
internal const val MEDIA_SESSION_ARTWORK_PX = 512

/**
 * Mirrors [session] into the OS media controls, and returns the disposer.
 *
 * The `<audio>` element is never in the document ([HtmlAudioPlayer]), so the browser has no UI
 * to attach to on its own — a media key, a lock screen and a Bluetooth button all reach the
 * page only through this API. Metadata follows the loaded book (which changes once per book) and
 * the position follows [PlaybackSession.progress] (which changes on every tick), so the two are
 * collected separately and `distinctUntilChanged` keeps the browser from being rewritten a few
 * times a second. The position stays book-scoped until the OS surfaces move to the chapter (PR 6).
 */
internal fun bindMediaSession(
    session: PlaybackSession,
    bridge: MediaSessionBridge,
    scope: CoroutineScope,
): () -> Unit {
    bridge.setHandler("play", session.onPlayPause)
    bridge.setHandler("pause", session.onPlayPause)
    bridge.setHandler("seekbackward", session.onSkipBack)
    bridge.setHandler("seekforward", session.onSkipForward)
    // Wired to the skip controls, not to chapters: what a "track" means for an audiobook is a
    // product call, and a next-chapter jump under a next-track button would surprise anyone whose
    // headphones use it to move on.
    bridge.setHandler("previoustrack", session.onSkipBack)
    bridge.setHandler("nexttrack", session.onSkipForward)
    bridge.setSeekToHandler(session.onSeek)

    val loaded = session.state.map { it as? PlayerUiState.Loaded }
    val job =
        scope.launch {
            launch {
                loaded
                    .map { it?.book }
                    .distinctUntilChanged { old, new -> old?.id == new?.id && old?.title == new?.title }
                    .collect { book -> book?.let { bridge.publish(it) } }
            }
            launch {
                loaded
                    .map { it?.phase == PlayerPhase.Playing || it?.phase == PlayerPhase.SleepFading }
                    .distinctUntilChanged()
                    .collect { bridge.setPlaying(it) }
            }
            launch {
                combine(loaded, session.progress) { state, progress -> state?.let { it to progress } }
                    .collect { pair ->
                        pair?.let { (state, progress) ->
                            bridge.setPosition(progress.bookDurationMs, progress.bookPositionMs, state.settings.speed)
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

/** Tells the OS what is playing: the book's title, who wrote it (or read it), its first series, its cover. */
private fun MediaSessionBridge.publish(book: PlayerBook) {
    setMetadata(
        title = book.title,
        artist =
            book.authors.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }
                ?: book.narrators.joinToString(", ") { it.name },
        album =
            book.series
                .firstOrNull()
                ?.name
                .orEmpty(),
        artworkUrl = coverUrl(book.id, book.coverHash, MEDIA_SESSION_ARTWORK_PX),
    )
}

/** Every action the OS controls can send that this app answers — registered and cleared together. */
private val TRANSPORT_ACTIONS =
    listOf("play", "pause", "seekbackward", "seekforward", "previoustrack", "nexttrack")

/** The one artwork rung declared to the OS — the lock-screen size the cover endpoint can serve. */
private const val ARTWORK_SIZES = "512x512"

private const val MS_PER_SECOND = 1000.0
