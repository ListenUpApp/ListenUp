package com.calypsan.listenup.web.playback

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
}

/** The hls.js class itself, as the module's default export — the static half of its API. */
internal external interface HlsClass {
    /** Whether this browser has the Media Source Extensions hls.js needs. */
    fun isSupported(): Boolean
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

    /** A fresh player instance. */
    fun create(): HlsPlayer {
        val constructor = hlsClass
        return js("new constructor()").unsafeCast<HlsPlayer>()
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
}

/** hls.js's own event name for a playback error. */
private const val HLS_ERROR_EVENT = "hlsError"

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
 * @param onFatalError invoked when hls.js gives up; the argument is a diagnostic string.
 * @throws IllegalStateException when the browser has neither MSE nor native HLS — a state the
 *   caller must surface, because nothing else can make this segment audible.
 */
internal fun attachHls(
    library: HlsLibrary?,
    element: HTMLMediaElement,
    url: String,
    onFatalError: (String) -> Unit,
): HlsHandle {
    if (library != null && library.isSupported()) {
        val hls = library.create()
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
