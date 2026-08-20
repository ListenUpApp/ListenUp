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
external class Hls {
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
external interface HlsErrorEvent {
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
 * [destroy].
 */
class HlsHandle internal constructor(
    private val hls: Hls?,
) {
    /** Release the underlying hls.js instance, if this attachment made one. */
    fun destroy() {
        hls?.destroy()
    }
}

private const val HLS_MIME = "application/vnd.apple.mpegurl"

/**
 * Point [element] at an HLS playlist, natively where the browser can and through hls.js where
 * it cannot.
 *
 * **Native first.** Safari decodes HLS in a bare element; using `src` there keeps playback on
 * the platform decoder instead of pushing every segment through MSE in JavaScript.
 *
 * The probe accepts any non-empty `canPlayType` answer, `"maybe"` included — deliberately
 * unlike `PlatformCodecCapabilities.js.kt`, which demands `"probably"`. There, a wrong "yes"
 * strands a listener in silence hours into a book. Here, a wrong "yes" fails loudly at attach
 * time, on the element's own `error` event, seconds after the tap.
 *
 * @param onFatalError invoked when hls.js gives up; the argument is a diagnostic string.
 * @throws IllegalStateException when the browser has neither native HLS nor MSE — a state the
 *   caller must surface, because nothing else can make this segment audible.
 */
fun attachHls(
    element: HTMLMediaElement,
    url: String,
    onFatalError: (String) -> Unit,
): HlsHandle {
    if (element.canPlayType(HLS_MIME).toString().isNotEmpty()) {
        element.src = url
        return HlsHandle(hls = null)
    }
    check(Hls.isSupported()) { "This browser supports neither native HLS nor MSE." }
    val hls = Hls()
    hls.on(HLS_ERROR_EVENT) { _, data ->
        if (data.fatal) onFatalError("${data.type}: ${data.details}")
    }
    hls.loadSource(url)
    hls.attachMedia(element)
    return HlsHandle(hls)
}
