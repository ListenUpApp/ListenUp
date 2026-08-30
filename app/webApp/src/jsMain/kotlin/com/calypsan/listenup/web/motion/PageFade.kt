package com.calypsan.listenup.web.motion

import kotlinx.browser.window
import org.w3c.dom.Element

/**
 * Fades the shell's content region when the reader moves to a different page.
 *
 * ⛔ **Opacity only. Never a transform.** A translate would read better and would break the cover
 * flight: [flyHeroInto] measures `getBoundingClientRect()` on the destination hero one frame after
 * it mounts, and a transform on any ancestor offsets a descendant's rect. The hero would then fly
 * to a position the page was still animating away from. Opacity changes no geometry, so the two
 * motions compose instead of fighting.
 *
 * ⛔ **Not a View Transition**, for the reason recorded in [HeroFlight] and `WebAppRoot`: the API
 * needs the DOM change to land inside its update callback, and Compose HTML cannot render in
 * there. This drives the same Web Animations API the flight does — it animates whatever is on
 * screen, whenever the framework gets round to putting it there.
 */
internal fun fadePageIn(element: Element) {
    if (prefersLessMotion()) return

    val keyframes =
        arrayOf(
            js("{}").unsafeCast<Any>().also { it.asDynamic().opacity = "0" },
            js("{}").unsafeCast<Any>().also { it.asDynamic().opacity = "1" },
        )
    val options = js("{}")
    options.duration = FADE_MS
    options.easing = FADE_EASING
    element.asDynamic().animate(keyframes, options)
}

/**
 * Whether this page change is one the reader should see marked.
 *
 * Only a change of PAGE counts, not of route. `/library?sort=title` → `/library?sort=added` is the
 * same page rearranging itself, and fading the whole region for it would punish the reader for
 * using a control — the very thing that makes an interface feel sluggish rather than alive. The
 * caller therefore keys on the page segment, and this exists to say so where it is easy to find.
 */
internal fun isPageChange(
    from: String?,
    to: String,
): Boolean = from != null && from != to

/** Matches [HeroFlight]'s own check: a reader who asked for less motion gets the page already there. */
private fun prefersLessMotion(): Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches

/**
 * Short. A page fade is punctuation, not an event — long enough that the change registers, short
 * enough that it never stands between the reader and content that has already arrived.
 */
private const val FADE_MS = 140

/** Easing out only: the page is arriving, and nothing is being left behind to accelerate away. */
private const val FADE_EASING = "cubic-bezier(0.2, 0, 0, 1)"
