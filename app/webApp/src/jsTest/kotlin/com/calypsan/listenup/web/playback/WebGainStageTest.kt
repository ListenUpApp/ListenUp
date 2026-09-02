package com.calypsan.listenup.web.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.url.URL
import kotlin.js.Promise

/** Slack for a number that has been through `10^(db/20)` and back. */
private const val GAIN_TOLERANCE = 0.001

/** +6 dB — two rungs up the shared ladder, and a linear gain no `element.volume` can express. */
private const val BOOST_DB = 6f

/** The linear form of [BOOST_DB]: about 1.995, which is why the element cannot carry it. */
private const val BOOST_LINEAR = 1.9952624

/** A negative gain — what a loudness-normalized loud book asks for. The element handles this alone. */
private const val ATTENUATE_DB = -6f

private const val ATTENUATE_LINEAR = 0.50118726

/** Halfway down a sleep fade. */
private const val HALF_FADE = 0.5f

private const val SEGMENT_MS = 400L

/** How often the decode poll looks, in ms. Short enough not to dominate a 400 ms clip. */
private const val DECODE_POLL_MS = 25L

private fun audioElement(): HTMLAudioElement = document.createElement("audio") as HTMLAudioElement

/**
 * An element with this page's own audio already on it.
 *
 * ⛔ Reach for this in any spec about *whether* the stage attaches. An empty element short-circuits
 * on "the audio has not arrived yet", which makes an assertion that nothing was routed pass for a
 * reason that has nothing to do with the rule under test. Callers revoke the object URL.
 */
private fun loadedElement(): HTMLAudioElement = audioElement().apply { src = silentWavObjectUrl(SEGMENT_MS) }

/**
 * Bytes the element has decoded — the only observation that separates playback from the appearance
 * of it, since `currentTime` advances on an element producing nothing. Chromium-only and
 * non-standard, so a browser without it reports 0 and the assertion fails rather than passing
 * vacuously. Both browser lanes here are Chromium.
 */
private fun decodedBytes(element: HTMLAudioElement): Double {
    val counter = element.asDynamic().webkitAudioDecodedByteCount
    return if (jsTypeOf(counter) == "number") counter.unsafeCast<Double>() else 0.0
}

/** Start the element and wait for the decoder to actually produce something. */
private suspend fun playUntilDecoding(element: HTMLAudioElement) {
    element.play().unsafeCast<Promise<Unit>?>()?.catch { Unit }
    withTimeout(AWAIT_TIMEOUT_MS) {
        while (decodedBytes(element) <= 0.0) delay(DECODE_POLL_MS)
    }
}

/**
 * A context that answers `resume()` but never actually runs — Safari's behaviour outside a user
 * gesture, and the exact case that must never reach `createMediaElementSource`.
 *
 * `routed` records the call rather than throwing on it, so the spec asserts the rule by name
 * instead of relying on an exception escaping from somewhere.
 */
private fun stuckContext(): WebAudioContext =
    js(
        "({ state: 'suspended', routed: false, resumeCount: 0, closed: false," +
            " destination: {}," +
            " resume: function(){ this.resumeCount++; return Promise.resolve(); }," +
            " close: function(){ this.closed = true; return Promise.resolve(); }," +
            " createGain: function(){ return { gain: { value: 1 }, connect: function(){} }; }," +
            " createMediaElementSource: function(){ this.routed = true; return { connect: function(){} }; } })",
    ).unsafeCast<WebAudioContext>()

/**
 * Whether [stuckContext] was asked to route the element — the irreversible call.
 *
 * Read through a typed helper rather than `asDynamic().routed shouldBe false` at the call site:
 * an infix `shouldBe` on a dynamic receiver compiles to a JS property lookup and fails at runtime
 * with "shouldBe is not a function", which reads as a Kotest problem rather than as this one.
 */
private fun WebAudioContext.wasRouted(): Boolean = asDynamic().routed.unsafeCast<Boolean>()

/** How many times [stuckContext] was asked to resume — one attempt only, ever. */
private fun WebAudioContext.resumeCount(): Int = asDynamic().resumeCount.unsafeCast<Int>()

class WebGainStageTest :
    FunSpec({

        test("no boost never touches Web Audio at all") {
            // The overwhelmingly common case. Routing an element that needs no amplification would
            // spend the whole risk of `createMediaElementSource` for nothing.
            //
            // ⛔ The element is LOADED on purpose. With nothing loaded the stage returns early for
            // an unrelated reason ("wait for the audio"), so the same assertion would pass over a
            // stage that attaches at every gain — which is exactly what a sabotage proved.
            val element = loadedElement()
            val stage = WebGainStage(element) { error("must not open an audio context") }

            stage.applyGainDb(0f)

            stage.isAttached shouldBe false
            element.volume shouldBe 1.0
            stage.unavailable.value shouldBe false

            URL.revokeObjectURL(element.src)
        }

        test("turning a book down is the element's own job, not a graph's") {
            // A negative normalization gain is attenuation, which `element.volume` does exactly.
            // Loaded, for the reason above: an empty element would pass whatever the rule was.
            val element = loadedElement()
            val stage = WebGainStage(element) { error("must not open an audio context") }

            stage.applyGainDb(ATTENUATE_DB)

            stage.isAttached shouldBe false
            element.volume shouldBe ATTENUATE_LINEAR.plusOrMinus(GAIN_TOLERANCE)

            URL.revokeObjectURL(element.src)
        }

        test("a fade never opens a context, however deep it goes") {
            // The fade runs on a 100 ms clock. An awaited context resume has no business there,
            // and a fade only ever turns a book down — which the element already does.
            val element = loadedElement()
            val stage = WebGainStage(element) { error("must not open an audio context") }

            stage.applyFade(0f)

            stage.isAttached shouldBe false
            element.volume shouldBe 0.0

            URL.revokeObjectURL(element.src)
        }

        test("amplification attaches, and the gain node is the thing that gets the number") {
            val element = audioElement()
            val url = silentWavObjectUrl(SEGMENT_MS)
            element.src = url
            val stage = WebGainStage(element)

            stage.applyGainDb(BOOST_DB)

            stage.isAttached shouldBe true
            // Read off the node, not off the stage's own arithmetic: a stage that computed the
            // right number and never wrote it would sound exactly like no boost at all.
            stage.nodeGain.shouldNotBeNull().toDouble() shouldBe BOOST_LINEAR.plusOrMinus(GAIN_TOLERANCE)
            // The element is pinned at unity so the two multipliers cannot both apply.
            element.volume shouldBe 1.0

            URL.revokeObjectURL(url)
        }

        test("a fade multiplies with a boost rather than replacing it") {
            // ⛔ The failure this prevents: a sleep fade writing `element.volume` directly while a
            // boost is attached would descend on a channel the audio no longer travels through —
            // the book would keep playing, at full volume, through a timer that said it had faded.
            val element = audioElement()
            val url = silentWavObjectUrl(SEGMENT_MS)
            element.src = url
            val stage = WebGainStage(element)

            stage.applyGainDb(BOOST_DB)
            stage.applyFade(HALF_FADE)

            stage.nodeGain.shouldNotBeNull().toDouble() shouldBe
                (BOOST_LINEAR * HALF_FADE).plusOrMinus(GAIN_TOLERANCE)
            element.volume shouldBe 1.0

            URL.revokeObjectURL(url)
        }

        test("a context that will not run is never routed, and says so") {
            // ⛔ THE rule. `createMediaElementSource` is irreversible: routing into a suspended
            // context silences the book for the rest of the session with no way back. Attaching is
            // allowed only after the context has said it is running.
            val element = audioElement()
            val url = silentWavObjectUrl(SEGMENT_MS)
            element.src = url
            val context = stuckContext()
            val stage = WebGainStage(element) { context }

            stage.applyGainDb(BOOST_DB)

            context.wasRouted() shouldBe false
            stage.isAttached shouldBe false
            // The element is left exactly as it was, still audible.
            element.volume shouldBe 1.0
            // And the listener is told, because a boost that silently did nothing is the quiet lie.
            stage.unavailable.value shouldBe true

            URL.revokeObjectURL(url)
        }

        test("a failed attach is never retried") {
            // A retry loop on the play path is a way to eventually route an element into a context
            // that then fails for some other reason. One attempt, ever.
            val element = audioElement()
            val url = silentWavObjectUrl(SEGMENT_MS)
            element.src = url
            val context = stuckContext()
            val stage = WebGainStage(element) { context }

            stage.applyGainDb(BOOST_DB)
            stage.applyGainDb(BOOST_DB)
            stage.applyGainDb(BOOST_DB)

            context.resumeCount() shouldBe 1

            URL.revokeObjectURL(url)
        }

        test("going back to no boost stops reporting the browser's refusal") {
            // The warning answers "your boost did not apply". Once nothing is being asked for
            // there is nothing to answer, and a standing caveat would be noise.
            val element = audioElement()
            element.src = silentWavObjectUrl(SEGMENT_MS)
            val stage = WebGainStage(element) { stuckContext() }

            stage.applyGainDb(BOOST_DB)
            stage.unavailable.value shouldBe true

            stage.applyGainDb(0f)
            stage.unavailable.value shouldBe false
        }

        test("a boost that arrives before the audio waits for it rather than giving up") {
            // ⛔ Not a refusal — a "not yet". `PlaybackManagerImpl` publishes `effectiveGainDb`
            // inside prepare, which is BEFORE the element gets a source, so every cold start
            // applies a boost to an empty element first. Treating that as a permanent failure
            // left a boosted book unboosted for the whole session.
            val element = audioElement()
            val stage = WebGainStage(element)

            stage.applyGainDb(BOOST_DB)

            stage.isAttached shouldBe false
            // Crucially NOT reported as unavailable: nothing has been refused yet.
            stage.unavailable.value shouldBe false

            val url = silentWavObjectUrl(SEGMENT_MS)
            element.src = url
            stage.applyGainDb(BOOST_DB)

            stage.isAttached shouldBe true
            stage.nodeGain.shouldNotBeNull().toDouble() shouldBe BOOST_LINEAR.plusOrMinus(GAIN_TOLERANCE)

            URL.revokeObjectURL(url)
        }

        test("a cross-origin source is refused rather than silently muted") {
            // ⛔ A media element the page cannot read taints the graph and outputs silence — not an
            // error. Checked before routing, because there is no after.
            val element = audioElement()
            element.src = "https://cdn.example.invalid/book/one.m4a"
            val context = stuckContext()
            val stage = WebGainStage(element) { context }

            stage.applyGainDb(BOOST_DB)

            context.wasRouted() shouldBe false
            context.resumeCount() shouldBe 0
            stage.unavailable.value shouldBe true
        }

        test("a boosted book keeps decoding — the graph carries the audio, it does not swallow it") {
            // The assertion that would have caught a real silencing: `currentTime` advances on an
            // element producing nothing, so only the decoder's own byte counter can tell the
            // difference between playing and the appearance of it.
            val element = audioElement()
            val url = silentWavObjectUrl(SEGMENT_MS)
            element.src = url
            val stage = WebGainStage(element)

            stage.applyGainDb(BOOST_DB)
            stage.isAttached shouldBe true
            playUntilDecoding(element)

            decodedBytes(element) shouldBeGreaterThan 0.0

            element.pause()
            URL.revokeObjectURL(url)
        }

        test("resetting drops the last book's boost without dismantling the graph") {
            // Attachment is permanent, but the multiplier is not: a new book must not inherit the
            // previous one's boost in the window before its own `effectiveGainDb` arrives.
            val element = audioElement()
            val url = silentWavObjectUrl(SEGMENT_MS)
            element.src = url
            val stage = WebGainStage(element)

            stage.applyGainDb(BOOST_DB)
            stage.reset()

            stage.isAttached shouldBe true
            stage.nodeGain.shouldNotBeNull().toDouble() shouldBe 1.0.plusOrMinus(GAIN_TOLERANCE)
            element.volume shouldBe 1.0

            URL.revokeObjectURL(url)
        }
    })
