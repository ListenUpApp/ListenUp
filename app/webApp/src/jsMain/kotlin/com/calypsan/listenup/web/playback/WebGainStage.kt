package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.loudness.VolumeGain
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.url.URL
import kotlin.js.Promise

/**
 * As much of the browser's Web Audio API as a gain stage needs, typed rather than `dynamic`.
 *
 * ⛔ Typed on purpose. A `dynamic` node dispatches every call through JS property lookup, so
 * `node?.let { }` compiles to a call to a `let` method the object does not have — it returns
 * undefined instead of failing, which turns a null-safety idiom into a silent wrong answer.
 */
internal external interface WebAudioNode {
    fun connect(destination: dynamic)
}

/** One automatable parameter. Only `value` is set here — see [WebGainStage.publish]'s note on ramps. */
internal external interface WebAudioParam {
    var value: Float
}

/** A gain node: one input, one output, one multiplier. */
internal external interface WebGainNode : WebAudioNode {
    val gain: WebAudioParam
}

/** As much of the browser's `AudioContext` as a gain stage needs. */
internal external interface WebAudioContext {
    /** `"suspended"`, `"running"` or `"closed"`. */
    val state: String

    val destination: dynamic

    fun createMediaElementSource(element: HTMLAudioElement): WebAudioNode

    fun createGain(): WebGainNode

    fun resume(): Promise<Unit>

    fun close(): Promise<Unit>
}

/**
 * A fresh `AudioContext`, or null in a browser that has none.
 *
 * `webkitAudioContext` is still the only spelling on Safari before 14.1, and a listener on one of
 * those gets no boost rather than a thrown `ReferenceError` on the play path.
 */
internal fun browserAudioContext(): WebAudioContext? =
    js(
        "(function(){" +
            "var C=(typeof AudioContext!=='undefined')?AudioContext:" +
            "((typeof webkitAudioContext!=='undefined')?webkitAudioContext:null);" +
            "return C?new C():null;})()",
    ).unsafeCast<WebAudioContext?>()

/**
 * The one place a volume multiplier reaches the browser, whether it came from loudness gain or
 * from a sleep-timer fade.
 *
 * ## Why anything more than `element.volume`
 *
 * `HTMLMediaElement.volume` cannot exceed 1.0 — the setter throws outside `[0, 1]` — so it can
 * turn a book down and never up. Amplification needs Web Audio: the element routed through a
 * `GainNode` whose gain is the linear form of [VolumeGain.effectiveGainDb], which is the same
 * number Android hands its `GainAudioProcessor` and iOS hands its audio engine.
 *
 * ## Why it is not attached up front
 *
 * `createMediaElementSource` is **irreversible and one-way**. Once an element is routed into a
 * graph its audio no longer reaches the speakers on its own, so a context that never reaches
 * `running` — Safari's rule is that one is resumed inside a user gesture or not at all — leaves
 * the book permanently silent with no way back. Silence a listener cannot explain is the single
 * worst outcome this player has, worse by a distance than a boost that did not apply.
 *
 * Three rules follow, and they are the whole design:
 *
 *  1. **Attenuation never attaches.** A gain at or below 0 dB is exactly what `element.volume`
 *     already does, so the common cases — no boost at all, a fade, a negative normalization gain —
 *     never touch Web Audio and carry none of its risk. Only amplification needs the graph.
 *  2. **Resume, verify, and only then route.** [attach] awaits `resume()` and requires
 *     `state == "running"` *before* it calls `createMediaElementSource`. A context that will not
 *     run is closed and the element is left exactly as it was, still playing.
 *  3. **One attempt, ever.** A failed attach sets [attachFailed] and is never retried, because a
 *     retry loop on the play path is a way to eventually route an element into a context that then
 *     fails for a different reason.
 *
 * A cross-origin source is refused for the same reason: routing a media element the page cannot
 * read taints the graph and outputs silence. Same-origin is the normal case — the production
 * server serves the bundle and the audio — so this only ever withholds boost from a split
 * deployment, which is the right way round.
 *
 * [unavailable] is how the listener finds out. Asking for +6 dB and silently getting none is the
 * kind of quiet lie the rest of this player is built to avoid.
 */
internal class WebGainStage(
    private val element: HTMLAudioElement,
    private val openContext: () -> WebAudioContext? = ::browserAudioContext,
) {
    /**
     * Whether a boost was asked for and could not be given.
     *
     * False while no boost is wanted, so a listener who never asked is never told about a
     * limitation that has not cost them anything.
     */
    val unavailable: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** The gain the player wants applied, in dB. Amplification above 0, attenuation below. */
    private var gainDb: Float = NO_GAIN_DB

    /** The sleep fade's multiplier, 0..1. Multiplies with [gainDb] rather than replacing it. */
    private var fadeLevel: Float = UNITY

    private var context: WebAudioContext? = null
    private var gainNode: WebGainNode? = null
    private var attachFailed: Boolean = false

    /** Whether the element is routed through Web Audio. Read by specs; nothing else needs it. */
    internal val isAttached: Boolean
        get() = gainNode != null

    /** The multiplier currently in force, wherever it is being applied. */
    internal val appliedGain: Float
        get() = VolumeGain.dbToLinear(gainDb) * fadeLevel

    /**
     * What the gain node was actually told, or null while nothing is attached.
     *
     * Read straight off the node rather than off [appliedGain], so a spec proves the multiplier
     * reached Web Audio rather than that this class computed one.
     */
    internal val nodeGain: Float?
        get() = gainNode?.gain?.value

    /**
     * Apply [db] of loudness gain from now on, attaching the graph if amplification needs it.
     *
     * Suspends because the attach does: a context has to be given the chance to resume, and
     * awaiting that is what makes rule 2 above possible.
     */
    suspend fun applyGainDb(db: Float) {
        gainDb = db
        if (VolumeGain.dbToLinear(db) > UNITY && !isAttached) attach()
        publish()
    }

    /**
     * Apply the sleep fade's [level], 0 (silent) to 1 (untouched).
     *
     * Deliberately not suspend and deliberately never attaches: a fade only ever turns a book
     * *down*, which the element does natively, and the fade runs on a 100 ms clock where an
     * awaited context resume has no business.
     */
    fun applyFade(level: Float) {
        fadeLevel = level.coerceIn(SILENT, UNITY)
        publish()
    }

    /**
     * Forget this book's gain, without dismantling anything.
     *
     * The graph stays attached once it exists — un-routing an element is not possible — but the
     * gain goes back to unity so a new book cannot inherit the last one's boost in the window
     * before its own `effectiveGainDb` arrives.
     */
    fun reset() {
        gainDb = NO_GAIN_DB
        fadeLevel = UNITY
        publish()
    }

    /**
     * Write the current multiplier wherever it is being applied, and refresh [unavailable].
     *
     * The node's `value` is set outright rather than ramped. `element.volume` — what every client
     * writes a fade through today — is equally abrupt, and a `setTargetAtTime` ramp would make the
     * value unreadable at the moment a spec asks for it. If zipper noise on a fade is ever
     * reported, a ramp plus a separately-recorded target is the refinement.
     */
    private fun publish() {
        val linear = appliedGain
        val node = gainNode
        if (node != null) {
            // Everything goes through the node once the node exists, and the element is pinned at
            // unity. Splitting the multiplier across both would apply the fade twice on a browser
            // where `volume` still reaches the graph, and lose it entirely on one where it does
            // not — and which of those a browser does is not worth having to know.
            element.volume = UNITY.toDouble()
            node.gain.value = linear
        } else {
            // Attenuation only. An unattached stage cannot amplify, so a linear gain above unity
            // clamps here — [unavailable] is what says so out loud.
            element.volume = linear.coerceIn(SILENT, UNITY).toDouble()
        }
        unavailable.value = attachFailed && VolumeGain.dbToLinear(gainDb) > UNITY
    }

    /**
     * Route the element through a gain node, or decide once and for all that it cannot be done.
     *
     * Every early return leaves the element untouched and audible; only the last three lines are
     * irreversible, and nothing reaches them until the context has said it is running.
     */
    private suspend fun attach() {
        if (attachFailed) return
        // A `when` rather than two ifs, so a fourth kind of source cannot be added without a
        // decision being made about whether it may be routed.
        when (sourceEligibility()) {
            SourceEligibility.OWN -> {
                Unit
            }

            // Not a refusal — a "not yet". The gain arrives before the audio does on every cold
            // start (`PlaybackManagerImpl` publishes `effectiveGainDb` inside prepare, and the
            // element gets its source in the `startPlayback` that follows), so treating an empty
            // element as a permanent failure would leave a boosted book unboosted for the whole
            // session. The next application, once the source is in, attaches for real.
            SourceEligibility.NOT_YET -> {
                return
            }

            SourceEligibility.FOREIGN -> {
                attachFailed = true
                return
            }
        }
        val ctx = context ?: openContext()
        if (ctx == null) {
            attachFailed = true
            return
        }
        context = ctx
        try {
            ctx.resume().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            console.warn("Volume boost unavailable: the audio context refused to resume (${e.message}).")
        }
        if (ctx.state != RUNNING) {
            attachFailed = true
            // Nothing was routed, so closing costs nothing and releases the hardware.
            runCatching { ctx.close() }
            context = null
            return
        }
        val source = ctx.createMediaElementSource(element)
        val node = ctx.createGain()
        source.connect(node)
        node.connect(ctx.destination)
        gainNode = node
    }

    /**
     * Whether the audio now loaded is something this page is allowed to read.
     *
     * A cross-origin media element routed into a graph produces silence, not an error, so this is
     * checked before the routing rather than discovered after it.
     *
     * The three-way answer is the point: "nothing loaded" and "someone else's audio" are opposite
     * conclusions — one is worth waiting for and the other can never be allowed — and collapsing
     * them into a boolean is what made a boost set before the audio arrived fail permanently.
     */
    private fun sourceEligibility(): SourceEligibility {
        val source = element.currentSrc.ifEmpty { element.src }
        if (source.isEmpty()) return SourceEligibility.NOT_YET
        // `blob:` URLs carry the origin that created them, which is this page — that covers every
        // hls.js book, whose element source is a MediaSource blob.
        val bare = source.removePrefix(BLOB_SCHEME)
        val origin = runCatching { URL(bare, browserOrigin()).origin }.getOrNull()
        return if (origin == browserOrigin()) SourceEligibility.OWN else SourceEligibility.FOREIGN
    }
}

/** What the element currently has loaded, as far as routing it into a graph is concerned. */
private enum class SourceEligibility {
    /** Nothing loaded yet. Wait — the audio for a boosted book routinely arrives after its gain. */
    NOT_YET,

    /** This page's own audio. Safe to route. */
    OWN,

    /** Someone else's. Routing it would taint the graph and output silence, so it never happens. */
    FOREIGN,
}

/** The page's own origin, read per check rather than captured, so a spec can navigate between them. */
private fun browserOrigin(): String = window.location.origin

private const val BLOB_SCHEME = "blob:"

private const val RUNNING = "running"

private const val UNITY = 1f

private const val SILENT = 0f

/** Unity gain expressed in decibels — "apply nothing", not "silence". */
private const val NO_GAIN_DB = 0f
