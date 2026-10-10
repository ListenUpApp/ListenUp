package com.calypsan.listenup.web.motion

import org.w3c.dom.Element

/**
 * Fades the shell's content region when the reader moves to a different page.
 *
 * ⛔ **Opacity only. Never a transform.** A translate would read better and would break the cover
 * flight: [flyHeroInto] measures `getBoundingClientRect()` on the destination hero one frame after
 * it mounts, and a transform on any ancestor offsets a descendant's rect. Opacity changes no
 * geometry, so the two motions compose instead of fighting.
 *
 * ⛔ **Not a View Transition**, for the reason recorded in [HeroFlight] and `WebAppRoot`.
 */
internal fun fadePageIn(element: Element) {
    animateComposited(element, listOf(Keyframe.opacity(0.0), Keyframe.opacity(1.0)), MotionToken.QUICK)
}

/**
 * Whether this page change is one the reader should see marked. Only a change of PAGE counts, not
 * of route: `/library?sort=title` → `/library?sort=added` is the same page rearranging itself.
 */
internal fun isPageChange(
    from: String?,
    to: String,
): Boolean = from != null && from != to
