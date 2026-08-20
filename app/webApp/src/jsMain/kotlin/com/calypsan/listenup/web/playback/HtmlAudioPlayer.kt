package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackState
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.MediaError

/** Where a book-relative position lands: which segment, and how far into it. */
data class SegmentSeek(
    val index: Int,
    val offsetInSegmentMs: Long,
)

/** What a segment should be played from, and by which mechanism. */
sealed interface SegmentSource {
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
fun resolveSegment(
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
fun sourceFor(segment: AudioSegment): SegmentSource =
    segment.hlsUrl?.let { SegmentSource.Hls(it) } ?: SegmentSource.Direct(segment.url)

private const val MS_PER_SECOND = 1000.0

/**
 * The browser's [AudioPlayer]: one `HTMLAudioElement`, re-pointed at each segment in turn, with
 * hls.js standing in wherever the element cannot decode the file itself.
 *
 * State is read off the element's own events rather than inferred from the calls made into it.
 * A player that reports `Playing` because `play()` was called, while the browser silently
 * refused, is exactly the lie this codebase exists to avoid.
 */
class HtmlAudioPlayer : AudioPlayer {
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
     * metadata. Written by [seekWithinSegment] and by nothing else — a position that reaches the
     * element without passing through here is a position [applyPendingOffset] will overwrite.
     */
    private var pendingOffsetMs: Long? = null

    /** Whether the element was mid-playback when the current attachment started. */
    private var resumeOnReady: Boolean = false

    private var released: Boolean = false

    init {
        element.preload = "auto"
        element.addEventListener("loadedmetadata", { applyPendingOffset() })
        element.addEventListener("timeupdate", { publishPosition() })
        // A seek while paused produces no `timeupdate`, so without this the reported position
        // would be the one that was *asked* for rather than the one the element actually took.
        element.addEventListener("seeked", { publishPosition() })
        element.addEventListener("playing", { state.value = PlaybackState.Playing })
        element.addEventListener("waiting", { state.value = PlaybackState.Buffering })
        element.addEventListener("pause", { onPaused() })
        element.addEventListener("ended", { onSegmentEnded() })
        element.addEventListener("error", { reportElementError() })
    }

    override suspend fun load(segments: List<AudioSegment>) {
        if (segments.isEmpty()) {
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
        resumeOnReady = true
        element.play()
    }

    override fun pause() {
        resumeOnReady = false
        element.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (segments.isEmpty()) return
        val seek = resolveSegment(segments, positionMs)
        this.positionMs.value = segments[seek.index].offsetMs + seek.offsetInSegmentMs
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

    override fun releasePlayer() {
        released = true
        resumeOnReady = false
        releaseHls()
        element.pause()
        // `src = ""` would resolve against the document URL and fire a spurious error; removing
        // the attribute and re-loading is the spec's own "forget the current media" path.
        element.removeAttribute("src")
        element.load()
        state.value = PlaybackState.Idle
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

    private fun releaseHls() {
        hlsHandle?.destroy()
        hlsHandle = null
    }

    private fun applyPendingOffset() {
        pendingOffsetMs?.let { offset ->
            pendingOffsetMs = null
            element.currentTime = offset / MS_PER_SECOND
        }
        if (resumeOnReady) element.play()
    }

    private fun publishPosition() {
        val segment = segments.getOrNull(currentIndex) ?: return
        positionMs.value = segment.offsetMs + (element.currentTime * MS_PER_SECOND).toLong()
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
        state.value = PlaybackState.Error(message = "Playback error. $detail", isRecoverable = true)
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
