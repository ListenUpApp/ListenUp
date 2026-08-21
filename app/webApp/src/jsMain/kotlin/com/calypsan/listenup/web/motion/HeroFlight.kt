package com.calypsan.listenup.web.motion

import kotlinx.browser.window
import org.w3c.dom.Element

/**
 * A Flutter-`Hero`-style flight for the book cover: the tile the reader tapped appears to travel
 * into the detail page's hero, and back again on return.
 *
 * ⛔ **Deliberately NOT a View Transition, despite that being what the API is for.** The browser's
 * shared-element transition needs the DOM change to happen inside its update callback, and
 * **Compose HTML cannot render in there** — the browser suppresses rendering while the callback is
 * outstanding and Compose's scheduler needs a frame. Measured: with the change applied inside the
 * callback, the destination had still not rendered **361 ms** later, so both snapshots captured the
 * *old* page and the keyframes ran `matrix(1,0,0,1,280,178) 208px` to exactly the same value.
 * Applying the change *before* the call fails the other way, because Compose flushes it
 * synchronously and the "old" snapshot is then already the new page.
 *
 * So the flight is measured and animated directly: record where the cover started, and when the
 * destination cover mounts, run it from there to where it now is. That is the FLIP technique, it
 * predates View Transitions, and it does not care when any framework renders.
 *
 * The page crossfade still uses View Transitions — that one needs no shared element and works.
 */
private var origin: Origin? = null

/**
 * Which surface a cover is on. The flight is always between the two, never within one — without
 * that, setting the grid's hero id made the grid tile's own arrival hook fire first and consume the
 * origin it had just recorded, leaving the real destination nothing to fly from.
 */
internal enum class CoverSurface {
    GRID,
    HERO,
}

/** Where a cover was, on which surface, at the moment it was last seen. */
private data class Origin(
    val bookId: String,
    val surface: CoverSurface,
    val left: Double,
    val top: Double,
    val width: Double,
)

/**
 * Remembers where [element] is on screen, so the matching cover on the next page can fly from here.
 *
 * Recorded at click time rather than read later because by the time the destination mounts, this
 * element is gone — the grid has unmounted, and a rect measured from a detached node is zero.
 */
internal fun recordHeroOrigin(
    bookId: String,
    surface: CoverSurface,
    element: Element,
) {
    val rect = element.getBoundingClientRect()
    val width: Double = rect.width
    if (width <= 0.0) return
    origin =
        Origin(
            bookId = bookId,
            surface = surface,
            left = rect.left,
            top = rect.top,
            width = width,
        )
}

/**
 * Flies [element] in from wherever [recordHeroOrigin] last saw this book's cover, if that was the
 * book the reader just tapped.
 *
 * Consumes the origin: a flight happens once, on arrival. Without that, a later re-render of the
 * same page would replay it, and the cover would twitch every time the book's data changed.
 */
internal fun flyHeroInto(
    bookId: String,
    surface: CoverSurface,
    element: Element,
) {
    val from = origin ?: return
    // ⛔ Same-surface arrivals must NOT consume the origin. Opening a book marks the tapped tile as
    // the hero, which re-renders it and fires its own arrival hook — before the destination has
    // even mounted. Consuming here left the real hero with nothing to fly from, and because the
    // tile's own geometry equals the origin, the flight was a no-op that swallowed itself.
    if (from.surface == surface) return
    if (from.bookId != bookId) return
    origin = null
    if (prefersReducedMotion()) return

    // ⛔ Measured on the NEXT frame, not now. Compose's `ref` fires as the node is created, which
    // can be before the browser has laid it out — `getBoundingClientRect()` then returns zeros, the
    // width guard below bails, and the flight silently never happens. That is exactly what it did:
    // hooking `Element.prototype.animate` recorded not a single call.
    window.requestAnimationFrame { fly(element, from) }
}

private fun fly(
    element: Element,
    from: Origin,
) {
    val rect = element.getBoundingClientRect()
    val toWidth: Double = rect.width
    if (toWidth <= 0.0) return

    val scale = from.width / toWidth
    val dx: Double = from.left - rect.left
    val dy: Double = from.top - rect.top
    // Nothing to animate when the two are already in the same place — a flight of zero distance is
    // a frame of wasted work and a tiny flicker.
    if (dx == 0.0 && dy == 0.0 && scale == 1.0) return

    val keyframes =
        arrayOf(
            js("{}").unsafeCast<Any>().also {
                it.asDynamic().transform = "translate(${dx}px, ${dy}px) scale($scale)"
                it.asDynamic().transformOrigin = "top left"
            },
            js("{}").unsafeCast<Any>().also {
                it.asDynamic().transform = "translate(0px, 0px) scale(1)"
                it.asDynamic().transformOrigin = "top left"
            },
        )
    val options = js("{}")
    options.duration = FLIGHT_MS
    options.easing = FLIGHT_EASING
    element.asDynamic().animate(keyframes, options)
}

/** Motion here is decoration; a reader who asked for less of it gets the cover already in place. */
private fun prefersReducedMotion(): Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches

/** Long enough to read as travel rather than a jump, short enough not to delay the page. */
private const val FLIGHT_MS = 340

/** Decelerating: fast away from the grid, settling into the hero. */
private const val FLIGHT_EASING = "cubic-bezier(0.2, 0, 0, 1)"
