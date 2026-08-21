package com.calypsan.listenup.web.playback

import org.w3c.dom.HTMLMediaElement

/**
 * The slice of [hls.js](https://github.com/video-dev/hls.js) this player needs.
 *
 * `@JsModule` on an external class binds to the module's **default** export under
 * `useEsModules()` — the compiler emits `import Hls from 'hls.js'`, not a named import. That is
 * right here, because hls.js ships `export { …, Hls as default, … }`, but nothing in the type
 * system can confirm it: an external class compiles against no evidence whatsoever.
 * `HlsAttachmentTest` is what actually proves the binding, by constructing one in a browser.
 *
 * No `@JsNonModule`. The compiler accepts it without complaint, but it declares this class
 * usable from a plain `<script>` build — a build this module stopped producing when webpack
 * gave way to Vite, so it would be dead metadata asserting something untrue.
 */
@JsModule("hls.js")
internal external class Hls {
    /** Point the instance at an `.m3u8` playlist. Loading starts once media is attached. */
    fun loadSource(url: String)

    /** Bind the instance to a media element, feeding it segments through MSE. */
    fun attachMedia(element: HTMLMediaElement)

    /**
     * Subscribe to an hls.js event.
     *
     * Narrowed on purpose: [HlsErrorEvent] describes the payload of `"hlsError"`, which is the
     * only event this codebase subscribes to. Widen the type before subscribing to another.
     */
    fun on(
        event: String,
        listener: (event: String, data: HlsErrorEvent) -> Unit,
    )

    /** Tear down the instance's buffers, timers and network loop. */
    fun destroy()

    companion object {
        /** Whether this browser has the Media Source Extensions hls.js needs. */
        fun isSupported(): Boolean
    }
}

/** The payload hls.js hands to a `"hlsError"` listener. */
internal external interface HlsErrorEvent {
    /** Broad category, e.g. `networkError`, `mediaError`. */
    val type: String

    /** Specific cause, e.g. `manifestLoadError`. */
    val details: String

    /** `true` when hls.js has given up and playback has stopped. */
    val fatal: Boolean
}

/** hls.js's own event name for a playback error. */
private const val HLS_ERROR_EVENT = "hlsError"

/**
 * The lifetime of one attachment. An abandoned [Hls] instance keeps its buffers, timers and
 * network loop alive; one per segment across a forty-hour book is a leak that surfaces as a
 * hung tab rather than as any visible error. So every segment change and every teardown calls
 * [destroy] — including [HtmlAudioPlayer.reportHlsError], because a fatal error stops hls.js
 * without releasing anything it holds.
 */
internal class HlsHandle(
    private val hls: Hls?,
) {
    /**
     * Whether hls.js is driving this attachment, rather than the browser's own HLS decoder.
     *
     * Exposed so a spec can assert which branch was taken. Without it, [attachHls] silently
     * choosing native on a browser that cannot decode HLS looks exactly like a working attachment
     * until playback fails several layers downstream — which is precisely how it shipped once.
     */
    val usesHlsJs: Boolean get() = hls != null

    /** Release the underlying hls.js instance, if this attachment made one. */
    fun destroy() {
        hls?.destroy()
    }
}

private const val HLS_MIME = "application/vnd.apple.mpegurl"

/**
 * Point [element] at an HLS playlist, through hls.js wherever the browser can run it.
 *
 * **hls.js first, native only as the fallback** — the reverse of the obvious ordering, for a
 * reason worth stating plainly: `canPlayType` cannot be used to detect native HLS. Chromium
 * answers `"maybe"` for `application/vnd.apple.mpegurl` and cannot decode HLS at all (verified in
 * this lane's Chromium 151, which answers `"probably"` for AAC in the same breath, so it is
 * discriminating — just not usefully). An earlier version of this function trusted any non-empty
 * answer, and the effect was that Chrome and Firefox — every browser this transcode path exists
 * to serve — took the native branch and hls.js was dead code.
 *
 * So the branch hinges on [Hls.isSupported], which asks whether Media Source Extensions exist.
 * That is a capability check with no ambiguity, and it lands correctly everywhere that matters:
 *
 * - **Chrome, Firefox, Edge** — MSE present, so hls.js drives. This is the requirement.
 * - **Safari on iPhone** — historically no MSE, so [Hls.isSupported] is false and the native
 *   branch takes over, which is right: that platform decodes HLS itself.
 * - **Safari on macOS** — MSE present, so hls.js drives even though the platform decoder could
 *   have. That is a knowing trade. Keeping macOS Safari on its native decoder would mean
 *   branching on `canPlayType` again, and no Safari was available to establish what it answers;
 *   a design that is merely suboptimal on Safari beats one that is broken on Chrome.
 *
 * @param onFatalError invoked when hls.js gives up; the argument is a diagnostic string.
 * @throws IllegalStateException when the browser has neither MSE nor native HLS — a state the
 *   caller must surface, because nothing else can make this segment audible.
 */
internal fun attachHls(
    element: HTMLMediaElement,
    url: String,
    onFatalError: (String) -> Unit,
): HlsHandle {
    if (Hls.isSupported()) {
        val hls = Hls()
        hls.on(HLS_ERROR_EVENT) { _, data ->
            if (data.fatal) onFatalError("${data.type}: ${data.details}")
        }
        hls.loadSource(url)
        hls.attachMedia(element)
        return HlsHandle(hls)
    }
    check(element.canPlayType(HLS_MIME).toString().isNotEmpty()) {
        "This browser supports neither MSE nor native HLS."
    }
    element.src = url
    return HlsHandle(hls = null)
}
