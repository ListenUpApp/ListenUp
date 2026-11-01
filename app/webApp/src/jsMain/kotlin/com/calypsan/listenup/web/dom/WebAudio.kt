package com.calypsan.listenup.web.dom

import org.w3c.dom.HTMLAudioElement
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

/** One automatable parameter. Only `value` is set here — see `WebGainStage.publish`'s note on ramps. */
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
