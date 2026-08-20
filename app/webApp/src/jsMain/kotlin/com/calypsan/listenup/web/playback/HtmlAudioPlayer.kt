package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackState
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.MediaError
import kotlin.js.Promise

/** Where a book-relative position lands: which segment, and how far into it. */
internal data class SegmentSeek(
    val index: Int,
    val offsetInSegmentMs: Long,
)

/** What a segment should be played from, and by which mechanism. */
internal sealed interface SegmentSource {
    /** An HLS playlist — needs hls.js, or Safari's native support. */
    data class Hls(
        val url: String,
    ) : SegmentSource

    /** Plain bytes the element can take on `src` directly. */
    data class Direct(
        val url: String,
    ) : SegmentSource
}

/**
 * Book-relative → segment coordinates, clamped at both ends.
 *
 * Clamping rather than throwing is deliberate: a seek past the end arrives routinely from a
 * scrubber released at the last pixel, and an exception on the play path is the worst possible
 * answer to "the listener dragged slightly too far".
 */
internal fun resolveSegment(
    segments: List<AudioSegment>,
    bookPositionMs: Long,
): SegmentSeek {
    if (segments.isEmpty()) return SegmentSeek(index = 0, offsetInSegmentMs = 0)
    if (bookPositionMs <= 0) return SegmentSeek(index = 0, offsetInSegmentMs = 0)
    val last = segments.lastIndex
    segments.forEachIndexed { index, segment ->
        val end = segment.offsetMs + segment.durationMs
        if (bookPositionMs < end) {
            return SegmentSeek(index = index, offsetInSegmentMs = bookPositionMs - segment.offsetMs)
        }
    }
    return SegmentSeek(index = last, offsetInSegmentMs = segments[last].durationMs)
}

/**
 * HLS wins whenever the server sent one, with no platform branching: its presence means the
 * server was told this device needs a transcode, so preferring it is always right. The direct
 * url stays the fallback if the HLS attach fails.
 *
 * [AudioSegment.localPath] is deliberately not considered. On web it is always null — the
 * browser's `DownloadFileManager` throws on purpose, because offline audio in a browser is
 * undesigned — so a local-path branch here would be dead code pretending to be a feature.
 */
internal fun sourceFor(segment: AudioSegment): SegmentSource =
    segment.hlsUrl?.let { SegmentSource.Hls(it) } ?: SegmentSource.Direct(segment.url)

/**
 * The user-facing message for a rejected `play()`, or null when the rejection is benign.
 *
 * `AbortError` is the ordinary consequence of re-pointing the element while a play is pending —
 * exactly what a segment advance does — so surfacing it would paint an error over correct
 * playback. Every other rejection means the audio did not start, and silence the listener cannot
 * explain is the failure this player exists to prevent.
 */
internal fun playRefusalMessage(errorName: String): String? =
    when (errorName) {
        "AbortError" -> null
        "NotAllowedError" -> "Playback needs a tap to start. This browser blocks audio nobody asked for."
        else -> "Playback error. The browser refused to start audio ($errorName)."
    }

private const val MS_PER_SECOND = 1000.0

/**
 * The browser's [AudioPlayer]: one `HTMLAudioElement`, re-pointed at each segment in turn, with
 * hls.js standing in wherever the element cannot decode the file itself.
 *
 * Wherever the element can answer for itself, it does: `playing`, `pause`, `waiting`, `ended`,
 * `canplay` and `error` drive [state], so the player reports what the browser actually did rather
 * than what it was asked to do. The calls in — [load] and [attach] — only ever publish
 * `Buffering`, an admission of not-knowing-yet that one of those events then resolves. Every such
 * optimistic write needs an event that will correct it; one without is how a book ends up
 * spinning forever.
 */
internal class HtmlAudioPlayer : AudioPlayer {
    override val state: StateFlow<PlaybackState>
        field = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    override val positionMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    override val durationMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    /**
     * Never added to the document: audio needs no layout box, and an element in the tree invites
     * CSS — and the next person's `querySelector` — to reach it.
     */
    private val element: HTMLAudioElement = document.createElement("audio") as HTMLAudioElement

    private var segments: List<AudioSegment> = emptyList()
    private var currentIndex: Int = 0
    private var hlsHandle: HlsHandle? = null
    private var speed: Float = 1.0f

    /**
     * Where inside the current segment playback is meant to be, re-asserted once the element has
     * metadata. [seekWithinSegment] is the only thing that sets it to a position; only
     * [applyPendingOffset] clears it, once it has been applied.
     */
    private var pendingOffsetMs: Long? = null

    /** Whether the element was mid-playback when the current attachment started. */
    private var resumeOnReady: Boolean = false

    /**
     * Set by [releasePlayer] to stop late events reporting errors about a book nobody is listening
     * to any more, and cleared by [load]. Release is a return to the resting state, not a terminal
     * one — see [releasePlayer].
     */
    private var released: Boolean = false

    init {
        element.preload = "auto"
        element.addEventListener("loadedmetadata", { applyPendingOffset() })
        element.addEventListener("canplay", { onReady() })
        element.addEventListener("timeupdate", { publishPosition() })
        // A seek while paused produces no `timeupdate`, so without this the reported position
        // would be the one that was *asked* for rather than the one the element actually took.
        element.addEventListener("seeked", { publishPosition() })
        element.addEventListener("playing", { state.value = PlaybackState.Playing })
        element.addEventListener("waiting", { onWaiting() })
        element.addEventListener("pause", { onPaused() })
        element.addEventListener("ended", { onSegmentEnded() })
        element.addEventListener("error", { reportElementError() })
    }

    override suspend fun load(segments: List<AudioSegment>) {
        released = false
        if (segments.isEmpty()) {
            // Forget the previous book as well as refusing this one: leaving its segments in place
            // would let a later play() or seekTo() operate on content the caller has replaced.
            forgetContent()
            state.value = PlaybackState.Error(message = "Playback error. No audio segments were provided.")
            return
        }
        this.segments = segments
        durationMs.value = segments.sumOf { it.durationMs }
        positionMs.value = 0
        resumeOnReady = false
        attach(index = 0, offsetInSegmentMs = 0)
    }

    override fun play() {
        if (segments.isEmpty()) return
        // Playing a finished book restarts it, which is what a bare media element does for a
        // single file — the spec seeks to the start when the position is already the end. Without
        // this, a multi-segment book would restart at its LAST segment, because `currentIndex` is
        // still parked there.
        if (state.value == PlaybackState.Ended) seekTo(0)
        resumeOnReady = true
        startPlayback()
    }

    override fun pause() {
        resumeOnReady = false
        element.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (segments.isEmpty()) return
        val seek = resolveSegment(segments, positionMs)
        this.positionMs.value = segments[seek.index].offsetMs + seek.offsetInSegmentMs
        // Seeking out of a finished book un-finishes it. Leaving `Ended` standing would show a
        // transport bar reading "finished" with the scrubber back at the beginning.
        if (state.value == PlaybackState.Ended) state.value = PlaybackState.Paused
        if (seek.index != currentIndex) {
            attach(index = seek.index, offsetInSegmentMs = seek.offsetInSegmentMs)
        } else {
            seekWithinSegment(seek.offsetInSegmentMs)
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        element.playbackRate = speed.toDouble()
    }

    /**
     * Sets the underlying `<audio>` element's output volume (0.0 silent – 1.0 normal).
     *
     * Not part of [AudioPlayer] — Desktop's `FfmpegAudioPlayer` exposes no volume control at all,
     * so adding it there would be a no-op forced onto every other platform. `HTMLMediaElement`
     * carries a real `volume` property, so [WebPlaybackController] reaches this narrow method
     * directly instead of pretending the shared interface can do the job.
     *
     * Coerced into range: the element's `volume` setter throws `IndexSizeError` outside [0, 1],
     * and a sleep-timer fade-out computing a value a hair past either end must not crash playback
     * over a rounding error.
     */
    internal fun setVolume(volume: Float) {
        element.volume = volume.coerceIn(0f, 1f).toDouble()
    }

    /**
     * Return to the resting state: nothing loaded, nothing playing, every published value zeroed.
     *
     * Not terminal — [load] revives the instance — so a DI graph is free to hold one of these as a
     * singleton and release it at the end of each listening session.
     */
    override fun releasePlayer() {
        released = true
        forgetContent()
        state.value = PlaybackState.Idle
    }

    /** Detach whatever is loaded and zero everything that described it. Leaves [state] to the caller. */
    private fun forgetContent() {
        resumeOnReady = false
        pendingOffsetMs = null
        releaseHls()
        element.pause()
        // `src = ""` would resolve against the document URL and fire a spurious error; removing the
        // attribute and re-loading is the spec's own "forget the current media" path.
        //
        // `load()` is also what makes the caller's next state assignment stick. The media element
        // load algorithm empties the element's queued event tasks, so the `pause` dispatched a line
        // above is discarded instead of arriving later and flipping the state back to `Paused`.
        // Dropping this call would silently reintroduce that race — `released` does not cover it,
        // because `onPaused` never consults it.
        element.removeAttribute("src")
        element.load()
        segments = emptyList()
        currentIndex = 0
        speed = 1.0f
        element.playbackRate = 1.0
        positionMs.value = 0L
        durationMs.value = 0L
    }

    /** Point the element at [index], to be positioned at [offsetInSegmentMs] once it is ready. */
    private fun attach(
        index: Int,
        offsetInSegmentMs: Long,
    ) {
        releaseHls()
        currentIndex = index
        state.value = PlaybackState.Buffering
        when (val source = sourceFor(segments[index])) {
            is SegmentSource.Direct -> {
                element.src = source.url
            }

            is SegmentSource.Hls -> {
                try {
                    hlsHandle = attachHls(element, source.url, ::reportHlsError)
                } catch (unsupported: IllegalStateException) {
                    state.value = PlaybackState.Error(message = unsupported.message, isRecoverable = false)
                    return
                }
            }
        }
        element.playbackRate = speed.toDouble()
        seekWithinSegment(offsetInSegmentMs)
    }

    /**
     * The one path by which a within-segment position reaches the element.
     *
     * It both writes the position and records it. The write lands even before metadata arrives —
     * the HTML spec routes a `currentTime` set at `HAVE_NOTHING` into the element's *default
     * playback start position*, and Chromium honours it — but that is the spec for a plain `src`
     * load, and the HLS path hands start-position control to hls.js instead. Recording the intent
     * costs one field and makes [applyPendingOffset] re-assert it once the element is ready, so
     * neither path has to be trusted on its own.
     *
     * Recording it on *every* seek, not just on attach, is the load-bearing part: a same-segment
     * seek that left the recorded offset stale would be silently undone the moment
     * `loadedmetadata` fired. That is the shape of a resume — `load()` then `seekTo()` — for every
     * single-file `.m4b`, which is to say for most audiobooks.
     */
    private fun seekWithinSegment(offsetInSegmentMs: Long) {
        pendingOffsetMs = offsetInSegmentMs
        element.currentTime = offsetInSegmentMs / MS_PER_SECOND
    }

    /**
     * `element.play()` is typed `Unit` by the Kotlin DOM bindings, but the method really returns a
     * Promise — and that Promise rejects when an autoplay policy refuses the request. Left alone it
     * becomes an unhandled rejection in the console and nothing else, so the listener gets a
     * spinner that never resolves instead of a reason. iOS Safari refuses far more readily than
     * Chrome, and [applyPendingOffset] calls this from a media event, well outside any tap.
     */
    private fun startPlayback() {
        val started = element.play().unsafeCast<Promise<Unit>?>() ?: return
        started.catch { reason -> onPlayRejected(reason) }
    }

    private fun onPlayRejected(reason: Throwable) {
        if (released) return
        // The element gets the first and better word. Per the HTML spec, play() rejects with
        // NotSupportedError only when `element.error` is already MEDIA_ERR_SRC_NOT_SUPPORTED, so
        // this handler would otherwise overwrite a specific diagnosis ("could not decode the
        // audio") with a generic restatement of it — and mark a permanently broken source
        // retryable, which PlaybackManagerImpl turns into a Retry button that can only loop.
        if (state.value is PlaybackState.Error) return
        val name = reason.asDynamic().name as? String ?: ""
        val message = playRefusalMessage(name) ?: return
        resumeOnReady = false
        state.value = PlaybackState.Error(message = message, isRecoverable = true)
    }

    private fun releaseHls() {
        hlsHandle?.destroy()
        hlsHandle = null
    }

    private fun applyPendingOffset() {
        pendingOffsetMs?.let { offset ->
            pendingOffsetMs = null
            element.currentTime = offset / MS_PER_SECOND
        }
        if (resumeOnReady) startPlayback()
    }

    /**
     * Becoming playable carries no state-bearing event of its own — an element that was never
     * playing is not sent a `pause` — so a book that was loaded and not played would sit on the
     * `Buffering` that [attach] published, forever.
     */
    private fun onReady() {
        if (!resumeOnReady && state.value == PlaybackState.Buffering) {
            state.value = PlaybackState.Paused
        }
    }

    private fun publishPosition() {
        val segment = segments.getOrNull(currentIndex) ?: return
        positionMs.value = segment.offsetMs + (element.currentTime * MS_PER_SECOND).toLong()
    }

    private fun onWaiting() {
        // A buffer draining after a fatal hls.js error must not be dressed up as ordinary
        // buffering: that swaps a real diagnosis for a spinner nothing will ever clear.
        if (state.value !is PlaybackState.Error) state.value = PlaybackState.Buffering
    }

    private fun onPaused() {
        // The element fires `pause` on its way to `ended` too; `ended` owns that transition.
        if (!element.ended && state.value !is PlaybackState.Error) {
            state.value = PlaybackState.Paused
        }
    }

    private fun onSegmentEnded() {
        val next = currentIndex + 1
        if (next > segments.lastIndex) {
            resumeOnReady = false
            state.value = PlaybackState.Ended
            return
        }
        attach(index = next, offsetInSegmentMs = 0)
    }

    private fun reportHlsError(detail: String) {
        if (released) return
        // Fatal means hls.js has stopped; its buffers, timers and retry loop have not. Nothing else
        // will destroy this instance — a fatal error is neither a segment change nor a teardown —
        // so without this it runs until the tab closes.
        releaseHls()
        state.value =
            PlaybackState.Error(
                message = "Playback error. $detail",
                // Not retryable, because this player offers no way to retry. Destroying the
                // instance above makes hls.js detach the media, which strips `src` and reloads the
                // element, so a later play() waits on a promise that never settles. Flipping this
                // to true needs a re-attach path to exist first — advertising recovery that is not
                // implemented is the same lie as reporting a spinner.
                isRecoverable = false,
            )
    }

    private fun reportElementError() {
        if (released) return
        val error = element.error
        state.value =
            PlaybackState.Error(
                message = "Playback error. ${describe(error)}",
                // Only a network stall is worth re-firing blindly; a codec the browser cannot
                // decode will fail identically every time, and telling the listener to retry
                // would be a second lie on top of the first.
                isRecoverable = error?.code == MediaError.MEDIA_ERR_NETWORK,
            )
    }

    private fun describe(error: MediaError?): String =
        when (error?.code) {
            null -> "The browser stopped playback without saying why."
            MediaError.MEDIA_ERR_ABORTED -> "Loading was aborted."
            MediaError.MEDIA_ERR_NETWORK -> "The connection dropped while loading audio."
            MediaError.MEDIA_ERR_DECODE -> "This browser could not decode the audio."
            MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED -> "This browser does not support this audio format."
            else -> "The browser reported media error ${error.code}."
        }
}
