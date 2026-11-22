package com.calypsan.listenup.web.playback

import com.calypsan.listenup.api.result.MediaFailure
import kotlinx.coroutines.await
import org.w3c.dom.HTMLMediaElement
import kotlin.js.Promise

/**
 * One hls.js player instance — the slice this player needs.
 *
 * An external *interface*, not an `@JsModule` external class: a class bound with `@JsModule` compiles
 * to a static `import Hls from 'hls.js'`, which puts all of hls.js (about 1.5 MB unminified) in the
 * entry chunk that every visitor downloads before the first paint — for a library only a book the
 * browser cannot decode directly ever uses. The class now arrives through [loadHlsLibrary]'s dynamic
 * `import()`, which Vite splits into a chunk of its own.
 */
internal external interface HlsPlayer {
    /** Point the instance at an `.m3u8` playlist. Loading starts once media is attached. */
    fun loadSource(url: String)

    /** Bind the instance to a media element, feeding it segments through MSE. */
    fun attachMedia(element: HTMLMediaElement)

    /**
     * Subscribe to an hls.js event. The payload's shape depends on the event, so each listener
     * casts it to the one it subscribed for ([HlsErrorEvent] for `"hlsError"`).
     */
    fun on(
        event: String,
        listener: (event: String, data: dynamic) -> Unit,
    )

    /** Tear down the instance's buffers, timers and network loop. */
    fun destroy()
}

/** The hls.js class itself, as the module's default export — the static half of its API. */
internal external interface HlsClass {
    /** Whether this browser has the Media Source Extensions hls.js needs. */
    fun isSupported(): Boolean

    /** The configuration every instance starts from; read, never written. */
    val DefaultConfig: dynamic
}

/** What `import('hls.js')` resolves to. hls.js ships `export { …, Hls as default, … }`. */
private external interface HlsModule {
    val default: HlsClass
}

/**
 * hls.js, once it has been fetched: the class, and a way to make instances of it.
 *
 * `HlsAttachmentTest` is what proves the binding, by constructing one in a browser — the types here
 * compile against no evidence whatsoever, so a default export that turned out to be the module
 * namespace would only show up as "is not a constructor" at runtime.
 */
internal class HlsLibrary(
    private val hlsClass: HlsClass,
) {
    /** See [HlsClass.isSupported]. */
    fun isSupported(): Boolean = hlsClass.isSupported()

    /**
     * A fresh player instance, configured so a segment the server has refused for good fails at once
     * rather than after hls.js's half-minute of retries — see [retriesSegmentAfter].
     */
    fun create(): HlsPlayer {
        val constructor = hlsClass
        val config = configFor(hlsClass)
        return js("new constructor(config)").unsafeCast<HlsPlayer>()
    }
}

/**
 * Whether hls.js should re-request a media segment that failed with HTTP [status] (null when the
 * request got no status at all). A server error retries only when [MediaFailure] says the same
 * request can succeed (502, 503, 504); hls.js's own rules decide everything else.
 *
 * ⛔ hls.js retries a 500 six times over about thirty seconds by default, and the stall watch
 * gives up at ten, so a segment the server could never produce surfaced as a dropped connection —
 * with a Retry button that could only loop (#1742).
 */
internal fun retriesSegmentAfter(status: Int?): Boolean =
    status == null || status < HTTP_SERVER_ERROR || MediaFailure.of(status, body = null) == MediaFailure.Transient

private const val HTTP_SERVER_ERROR = 500

/**
 * hls.js's defaults with [retriesSegmentAfter] consulted before every media-segment retry. Copied
 * rather than edited in place: hls.js merges a user config shallowly, so its default objects are
 * shared by every instance, and writing into them would reconfigure each one made afterwards.
 */
private fun configFor(hlsClass: HlsClass): dynamic {
    val defaults = hlsClass.DefaultConfig.fragLoadPolicy.default
    val errorRetry = js("Object.assign({}, defaults.errorRetry)")
    errorRetry.shouldRetry = { _: dynamic, _: dynamic, _: dynamic, response: dynamic, retry: Boolean ->
        retry && retriesSegmentAfter(statusOf(response))
    }
    val policy = js("Object.assign({}, defaults)")
    policy.errorRetry = errorRetry
    val config = js("({})")
    config.fragLoadPolicy = js("({})")
    config.fragLoadPolicy.default = policy
    return config
}

/** The HTTP status in an hls.js loader response, or null when there is none. */
private fun statusOf(response: dynamic): Int? {
    if (response == null || response == undefined) return null
    val code = response.code
    return if (jsTypeOf(code) == "number") code.unsafeCast<Int>() else null
}

/**
 * The text of the body a failed request carried, when hls.js kept it. Media segments are fetched as
 * array buffers, so a server's typed error arrives as bytes to decode; anything else is not read.
 */
private fun bodyOf(networkDetails: dynamic): String? {
    if (networkDetails == null || networkDetails == undefined) return null
    val response = networkDetails.response
    return when {
        jsTypeOf(response) == "string" -> response.unsafeCast<String>()
        js("response instanceof ArrayBuffer").unsafeCast<Boolean>() -> js("new TextDecoder().decode(response)").unsafeCast<String>()
        else -> null
    }
}

/** The one in-flight or finished import, so every attachment after the first is free. */
private var hlsLibrary: Promise<HlsLibrary?>? = null

/**
 * Fetches hls.js on first use, or null when the chunk could not be loaded.
 *
 * Null rather than a throw, and not cached: a chunk that failed on a flaky network is worth asking
 * for again next time, and in the meantime [attachHls] falls back to the browser's own HLS support
 * — which on the one platform that has it (Safari on iPhone) is the decoder that would have been
 * used anyway.
 */
internal suspend fun loadHlsLibrary(): HlsLibrary? {
    val pending =
        hlsLibrary ?: importHls()
            .then({ module -> HlsLibrary(module.default) }, { null })
            .also { hlsLibrary = it }
    val loaded = pending.await()
    if (loaded == null) hlsLibrary = null
    return loaded
}

/**
 * A dynamic `import()`, which the bundler turns into a separate chunk fetched on demand. `js()`
 * because Kotlin has no syntax for it; a static `@JsModule` import is exactly what this replaces.
 */
private fun importHls(): Promise<HlsModule> = js("import('hls.js')").unsafeCast<Promise<HlsModule>>()

/** The payload hls.js hands to a `"hlsError"` listener. */
internal external interface HlsErrorEvent {
    /** Broad category, e.g. `networkError`, `mediaError`. */
    val type: String

    /** Specific cause, e.g. `manifestLoadError`. */
    val details: String

    /** `true` when hls.js has given up and playback has stopped. */
    val fatal: Boolean

    /** The failed request's `{ code, text }`, for a network error; absent otherwise. */
    val response: dynamic

    /** The loader's own request object (an `XMLHttpRequest`), whose `response` holds the reply body. */
    val networkDetails: dynamic
}

/**
 * A fatal hls.js failure: hls.js's own [type] and [details], and for a failed request the HTTP
 * [status] and reply [body] — which is where the server says whether trying again can help.
 */
internal data class HlsFatalError(
    val type: String,
    val details: String,
    val status: Int? = null,
    val body: String? = null,
)

/** hls.js's own event name for a playback error. */
private const val HLS_ERROR_EVENT = "hlsError"

/** hls.js's own event name for a media fragment that finished downloading: the stream is alive. */
private const val HLS_FRAG_LOADED_EVENT = "hlsFragLoaded"

/**
 * The lifetime of one attachment. An abandoned [HlsPlayer] instance keeps its buffers, timers and
 * network loop alive; one per segment across a forty-hour book is a leak that surfaces as a
 * hung tab rather than as any visible error. So every segment change and every teardown calls
 * [destroy] — including [HtmlAudioPlayer.reportHlsError], because a fatal error stops hls.js
 * without releasing anything it holds.
 */
internal class HlsHandle(
    private val hls: HlsPlayer?,
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
 * So the branch hinges on [HlsLibrary.isSupported], which asks whether Media Source Extensions exist.
 * That is a capability check with no ambiguity, and it lands correctly everywhere that matters:
 *
 * - **Chrome, Firefox, Edge** — MSE present, so hls.js drives. This is the requirement.
 * - **Safari on iPhone** — historically no MSE, so [HlsLibrary.isSupported] is false and the native
 *   branch takes over, which is right: that platform decodes HLS itself.
 * - **Safari on macOS** — MSE present, so hls.js drives even though the platform decoder could
 *   have. That is a knowing trade. Keeping macOS Safari on its native decoder would mean
 *   branching on `canPlayType` again, and no Safari was available to establish what it answers;
 *   a design that is merely suboptimal on Safari beats one that is broken on Chrome.
 *
 * [library] is hls.js as [loadHlsLibrary] fetched it, or null when it could not be — the caller
 * awaits that before the first HLS segment, so attaching stays synchronous from then on (a segment
 * advance fires from a media event, where there is nothing to suspend).
 *
 * @param onFatalError invoked when hls.js gives up, with what it said and, for a request, what the server replied.
 * @param onFragmentLoaded invoked each time hls.js lands a media fragment, however slowly: the progress a
 *   [PlaybackStallWatch] needs to tell a slow stream from a dead one.
 * @throws IllegalStateException when the browser has neither MSE nor native HLS — a state the
 *   caller must surface, because nothing else can make this segment audible.
 */
internal fun attachHls(
    library: HlsLibrary?,
    element: HTMLMediaElement,
    url: String,
    onFatalError: (HlsFatalError) -> Unit,
    onFragmentLoaded: () -> Unit = {},
): HlsHandle {
    if (library != null && library.isSupported()) {
        val hls = library.create()
        hls.on(HLS_ERROR_EVENT) { _, data ->
            val error = data.unsafeCast<HlsErrorEvent>()
            if (error.fatal) {
                onFatalError(HlsFatalError(error.type, error.details, statusOf(error.response), bodyOf(error.networkDetails)))
            }
        }
        hls.on(HLS_FRAG_LOADED_EVENT) { _, _ -> onFragmentLoaded() }
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
