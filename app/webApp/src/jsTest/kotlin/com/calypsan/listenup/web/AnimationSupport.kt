package com.calypsan.listenup.web

import kotlinx.browser.window
import org.w3c.dom.Element

/**
 * Test-side readers for the Web Animations API — the only honest evidence that a motion happened.
 *
 * Every earlier failure of web motion was silent (a rect of zeros, an origin never recorded), and
 * each looked identical to "working" from anywhere except the browser's own list of animations.
 */
internal fun Element.motions(): List<dynamic> = asDynamic().getAnimations().unsafeCast<Array<dynamic>>().toList()

/** The properties [animation]'s keyframes name, minus the browser's bookkeeping keys. */
internal fun animatedProperties(animation: dynamic): Set<String> {
    val frames = animation.effect.getKeyframes().unsafeCast<Array<dynamic>>()
    return frames
        .flatMap { frame -> js("Object").keys(frame).unsafeCast<Array<String>>().toList() }
        .filterNot { it in KEYFRAME_BOOKKEEPING }
        .toSet()
}

/** One field of [animation]'s timing — `duration`, `delay`, `easing`, `fill`. */
internal fun timingOf(
    animation: dynamic,
    field: String,
): dynamic = animation.effect.getTiming()[field]

/** [animation]'s duration in milliseconds. */
internal fun durationOf(animation: dynamic): Int = timingOf(animation, "duration").unsafeCast<Number>().toInt()

/**
 * Starts recording every element `Element.prototype.animate` is called on, from now until
 * [stopRecordingAnimations]. Polling `getAnimations()` can miss a 240 ms motion between two polls;
 * a record of the call cannot.
 */
internal fun startRecordingAnimations() {
    js(
        """
        (function () {
          if (Element.prototype.__luOriginalAnimate) return;
          var original = Element.prototype.animate;
          window.__luAnimated = [];
          Element.prototype.__luOriginalAnimate = original;
          Element.prototype.animate = function (keyframes, options) {
            window.__luAnimated.push(this);
            return original.call(this, keyframes, options);
          };
        })()
        """,
    )
}

/** Every element animated since [startRecordingAnimations], in call order. */
internal fun recordedAnimations(): List<Element> =
    (window.asDynamic().__luAnimated ?: js("[]")).unsafeCast<Array<Element>>().toList()

/** Puts `Element.prototype.animate` back. Safe to call when nothing is recording. */
internal fun stopRecordingAnimations() {
    js(
        """
        (function () {
          var original = Element.prototype.__luOriginalAnimate;
          if (!original) return;
          Element.prototype.animate = original;
          delete Element.prototype.__luOriginalAnimate;
          window.__luAnimated = [];
        })()
        """,
    )
}

private val KEYFRAME_BOOKKEEPING = setOf("offset", "easing", "composite", "computedOffset")
