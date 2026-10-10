package com.calypsan.listenup.web.motion

import kotlinx.browser.window
import org.w3c.dom.Element

/*
 * The web client's motion, in one place: three durations, one curve, one way to animate, and one
 * viewport check. Everything that moves in `:app:webApp` goes through here.
 *
 * CHARACTER — quick and quiet. The model is a good desktop web app: you would miss the motion if it
 * were gone, but you rarely notice it. One decelerating curve; no springs, no overshoot.
 *
 * RESTRAINT RULES — every motion answers to these:
 *   - Nothing loops.
 *   - Nothing animates by itself.
 *   - Something moves only because the reader caused it, or because it just changed while on screen.
 *
 * HARD LIMITS — all measured, none of them taste:
 *   - ⛔ No View Transitions API. Compose HTML cannot render inside its update callback (measured:
 *     361 ms and still unrendered); see HeroFlight.kt. Motion here is the Web Animations API.
 *   - ⛔ Composited properties only. [Keyframe] can say opacity and transform and nothing else, so
 *     no motion can lay the page out again sixty times a second.
 *   - ⛔ Viewport-scoped. A list-level effect touches only what [isOnScreen] says the reader can see:
 *     naming 1,204 grid items cost 12.2 s where the ~28 on screen cost 41 ms.
 *   - ⛔ The page region (`.shell-main`) never takes a transform; see PageArrival.kt.
 *   - ⛔ Reduced motion is checked HERE, so no call site can forget it. [animateComposited] then
 *     starts nothing and its [Motion] reports itself finished at once — which is why the end state of
 *     every motion must be the element's own resting style. A fill never holds the last frame.
 */

/** The three durations. Mirrored as `--motion-*` in `00-base.css`; `MotionTest` holds them equal. */
internal enum class MotionToken(
    val millis: Int,
    val cssProperty: String,
) {
    /** Presses, toggles, icon morphs — and the page region's fade. */
    QUICK(QUICK_MS, "--motion-quick"),

    /** Things appearing: a staggered card, a toast, a highlight. */
    ENTER(ENTER_MS, "--motion-enter"),

    /** Things travelling: page content settling, list reflow, shared elements. */
    MOVE(MOVE_MS, "--motion-move"),
}

/** The one curve: a fast decelerate. Mirrored as `--motion-ease`. */
internal const val MOTION_EASING = "cubic-bezier(0.2, 0, 0, 1)"

/** The CSS custom property that mirrors [MOTION_EASING]. */
internal const val MOTION_EASING_PROPERTY = "--motion-ease"

/**
 * One keyframe — and the reason this module can promise "composited only": there is no way to
 * spell anything but opacity and transform with it.
 */
internal class Keyframe private constructor(
    private val opacity: Double?,
    private val transform: String?,
) {
    /** This frame as the object `Element.animate` takes. [transformOrigin] is constant, never interpolated. */
    internal fun toJs(transformOrigin: String?): dynamic {
        val frame = js("{}")
        if (opacity != null) frame.opacity = opacity
        if (transform != null) frame.transform = transform
        if (transformOrigin != null) frame.transformOrigin = transformOrigin
        return frame
    }

    companion object {
        /** A frame that only fades. */
        fun opacity(value: Double): Keyframe = Keyframe(opacity = value, transform = null)

        /** A frame that only moves or scales. */
        fun transform(value: String): Keyframe = Keyframe(opacity = null, transform = value)

        /** A frame that does both. */
        fun of(
            opacity: Double,
            transform: String,
        ): Keyframe = Keyframe(opacity = opacity, transform = transform)
    }
}

/**
 * Whether a motion shows its first frame while it waits out its delay. Deliberately no `forwards`:
 * an end state held by a fill disappears the moment the animation is cancelled, and under reduced
 * motion there is no animation to hold it at all.
 */
internal enum class MotionFill(
    val value: String,
) {
    NONE("none"),

    /** For a staggered item: invisible until its turn, rather than visible, then gone, then back. */
    BACKWARDS("backwards"),
}

/** A started motion — or, under reduced motion, one that has already finished. */
internal class Motion(
    private val animation: dynamic,
) {
    /** Runs [action] once this motion has finished; at once if it never started. */
    fun whenFinished(action: () -> Unit) {
        if (animation == null) {
            action()
            return
        }
        animation.addEventListener("finish", { _: dynamic -> action() })
    }

    /** Stops this motion, leaving the element in its resting state. Nothing to stop if it never started. */
    fun cancel() {
        if (animation != null) animation.cancel()
    }
}

/**
 * Animates [element] through [keyframes] over [token] on the house curve.
 *
 * Under reduced motion nothing starts and the element is simply in its resting state, which is
 * every motion's end state (see the file header). [delayMs] is for a stagger, and [fill] decides
 * whether the first frame shows during that delay. [transformOrigin] is held constant on every frame.
 */
internal fun animateComposited(
    element: Element,
    keyframes: List<Keyframe>,
    token: MotionToken,
    delayMs: Double = 0.0,
    fill: MotionFill = MotionFill.NONE,
    transformOrigin: String? = null,
): Motion {
    if (prefersLessMotion()) return Motion(null)
    val frames = keyframes.map { it.toJs(transformOrigin) }.toTypedArray()
    val options = js("{}")
    options.duration = token.millis
    options.easing = MOTION_EASING
    options.delay = delayMs
    options.fill = fill.value
    return Motion(element.asDynamic().animate(frames, options))
}

/**
 * Set by specs only, to stand in for the OS setting a browser runner cannot change. Production never
 * writes it; `null` means "ask the browser".
 */
internal var reducedMotionOverride: Boolean? = null

/** Whether the reader asked for less motion. The one check: [animateComposited] makes it for every caller. */
internal fun prefersLessMotion(): Boolean = reducedMotionOverride ?: window.matchMedia(REDUCED_MOTION_QUERY).matches

/**
 * Whether any of [element] is inside its window's viewport — the single check every list-level
 * effect makes before touching an element. Zero-size boxes (detached, `display:none`,
 * `display:contents`) are never on screen.
 */
internal fun isOnScreen(element: Element): Boolean {
    val rect = element.getBoundingClientRect()
    if (rect.width <= 0.0 || rect.height <= 0.0) return false
    val view = element.ownerDocument?.defaultView ?: window
    return rect.bottom > 0.0 && rect.right > 0.0 && rect.top < view.innerHeight && rect.left < view.innerWidth
}

private const val REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)"

private const val QUICK_MS = 120

private const val ENTER_MS = 180

private const val MOVE_MS = 240
