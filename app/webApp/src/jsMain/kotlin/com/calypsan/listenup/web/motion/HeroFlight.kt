package com.calypsan.listenup.web.motion

import com.calypsan.listenup.web.nav.whenScrollSettled
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.Element

/**
 * A Flutter-`Hero`-style flight for a shared element: the tile the reader tapped appears to travel
 * into the detail page's hero, and back again on return. Book covers, series covers and contributor
 * avatars all fly this way, told apart by a key ([seriesHeroKey], [contributorHeroKey]; a book's key
 * is its id).
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
 * So the flight is measured and animated directly: record where the element started, and when the
 * destination mounts, run it from there to where it now is. That is the FLIP technique, it predates
 * View Transitions, and it does not care when any framework renders. It goes through
 * [animateComposited], so it is transform-only, runs for [MotionToken.MOVE], and under reduced motion
 * the element is simply already in place.
 *
 * ⛔ **It measures the settled page.** On Back the reader's scroll is restored over a few frames
 * (`ScrollMemory.kt`), and on a link the new page starts at the top; a flight measured before that
 * would land where the tile *was*. So arrival waits for [whenScrollSettled], then one more frame.
 *
 * ⛔ **It flies only into a connected, on-screen element** and otherwise leaves the origin alone. A
 * virtualised grid briefly renders a stand-in screenful from the top before scrolling to the reader's
 * place; the real tile mounts again after the scroll and is the one that flies.
 */
private var origin: Origin? = null

/**
 * Which surface an element is on. The flight is always between the two, never within one — without
 * that, setting the grid's hero id made the grid tile's own arrival hook fire first and consume the
 * origin it had just recorded, leaving the real destination nothing to fly from.
 */
internal enum class CoverSurface {
    GRID,
    HERO,
}

/**
 * The detail page's hero, while it is mounted. There is only ever one.
 *
 * Held so its position can be read on the way *out* of the page — see
 * [captureHeroOriginBeforeRouteChange] for why disposal is too late to read it.
 */
private var mountedHero: Pair<String, Element>? = null

/** Elements a flight has been promised to and has not yet started on. See [isLanding]. */
private val landing = mutableListOf<Element>()

/** Where an element was, on which surface, at the moment it was last seen. */
private data class Origin(
    val key: String,
    val surface: CoverSurface,
    val left: Double,
    val top: Double,
    val width: Double,
)

/** A series' flight key — namespaced so series "7" never flies into book "7"'s tile. */
internal fun seriesHeroKey(seriesId: String): String = "series:$seriesId"

/** A contributor's flight key. */
internal fun contributorHeroKey(contributorId: String): String = "contributor:$contributorId"

/**
 * Remembers where [element] is on screen, so the matching element on the next page can fly from here.
 *
 * Recorded at click time rather than read later because by the time the destination mounts, this
 * element is gone — the grid has unmounted, and a rect measured from a detached node is zero.
 */
internal fun recordHeroOrigin(
    key: String,
    surface: CoverSurface,
    element: Element,
) {
    val rect = element.getBoundingClientRect()
    val width: Double = rect.width
    if (width <= 0.0) return
    origin = Origin(key = key, surface = surface, left = rect.left, top = rect.top, width = width)
}

/**
 * Flies [element] in from wherever [recordHeroOrigin] last saw the element keyed [key], if that is the
 * one the reader just left.
 *
 * Consumes the origin on the flight it starts: a flight happens once, on arrival. Without that, a
 * later re-render of the same page would replay it, and the element would twitch every time the data
 * behind it changed.
 */
internal fun flyHeroInto(
    key: String,
    surface: CoverSurface,
    element: Element,
) {
    val from = origin ?: return
    // ⛔ Same-surface arrivals must NOT consume the origin. Opening a book marks the tapped tile as
    // the hero, which re-renders it and fires its own arrival hook — before the destination has
    // even mounted. Consuming here left the real hero with nothing to fly from.
    if (from.surface == surface) return
    if (from.key != key) return
    landing += element
    // ⛔ Measured after the scroll settles and on the NEXT frame. Compose's `ref` fires as the node
    // is created, which can be before the browser has laid it out — `getBoundingClientRect()` then
    // returns zeros and the flight silently never happens.
    whenScrollSettled { window.requestAnimationFrame { arrive(key, surface, element) } }
}

private fun arrive(
    key: String,
    surface: CoverSurface,
    element: Element,
) {
    landing.removeAll { it === element }
    val from = origin ?: return
    if (from.surface == surface || from.key != key) return
    if (!element.isConnected || !isOnScreen(element)) return
    origin = null
    fly(element, from)
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

    animateComposited(
        element,
        listOf(
            Keyframe.transform("translate(${dx}px, ${dy}px) scale($scale)"),
            Keyframe.transform("translate(0px, 0px) scale(1)"),
        ),
        MotionToken.MOVE,
        transformOrigin = "top left",
    )
}

/**
 * Whether a flight is about to land on [element] or something inside it. A stagger leaves such an
 * item alone: fading in a card whose cover is mid-flight would start the flight invisible.
 */
internal fun isLanding(element: Element): Boolean = landing.any { it === element || element.contains(it) }

/** Remembers the mounted detail hero, so its position can be read before the page goes away. */
internal fun trackHero(
    key: String,
    element: Element,
) {
    mountedHero = key to element
}

/** Forgets [element] as the hero, if it still is one. */
internal fun releaseHero(element: Element) {
    if (mountedHero?.second === element) mountedHero = null
}

/**
 * Records where the detail hero is right now, so the tile it returns to can fly back from it.
 *
 * ⛔ **Called on route change, NOT from the hero's `onDispose`.** Compose detaches the node during
 * `applyChanges` and dispatches remember-observers afterwards, so by the time an `onDispose` block
 * runs the element is out of the document and `getBoundingClientRect()` is all zeros — the width
 * guard in [recordHeroOrigin] then bailed and no origin was ever recorded. Measured: the outbound
 * flight called `Element.animate` once, the return leg called it zero times.
 *
 * Reading it here is safe because Compose renders on a later frame than the route change, so the
 * page being left is still laid out when this runs — for a breadcrumb click and a Back alike.
 */
internal fun captureHeroOriginBeforeRouteChange() {
    val hero = mountedHero
    if (hero == null) {
        // Leaving a page that has no hero — so any hero origin still pending belongs to a page
        // two navigations ago and must not fly. A GRID origin survives: a tile records one on the
        // click that starts the outbound flight, and this runs on the very navigation that click
        // triggers.
        if (origin?.surface == CoverSurface.HERO) origin = null
        return
    }
    recordHeroOrigin(hero.first, CoverSurface.HERO, hero.second)
}

/**
 * Makes this element a detail page's hero for [key]: it flies in from the tapped tile on arrival,
 * and is what the tile flies back from when the reader leaves.
 */
internal fun AttrsScope<*>.heroTarget(key: String) {
    ref { element ->
        flyHeroInto(key, CoverSurface.HERO, element)
        // Tracked, not measured: the return leg's origin is read at route-change time, while this
        // node is still laid out. See [captureHeroOriginBeforeRouteChange].
        trackHero(key, element)
        onDispose { releaseHero(element) }
    }
}

/** Makes this element the grid-side end of [key]'s flight: the hero flies home into it on return. */
internal fun AttrsScope<*>.heroTile(key: String) {
    ref { element ->
        flyHeroInto(key, CoverSurface.GRID, element)
        onDispose { }
    }
}

/** Specs only: forgets every origin, hero and pending landing. Production never calls it. */
internal fun forgetHeroFlight() {
    origin = null
    mountedHero = null
    landing.clear()
}
