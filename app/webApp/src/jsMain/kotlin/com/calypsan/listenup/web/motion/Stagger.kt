package com.calypsan.listenup.web.motion

import com.calypsan.listenup.web.nav.RouteChange
import com.calypsan.listenup.web.nav.lastRouteChange
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.Element
import org.w3c.dom.asList
import kotlin.js.Promise

/*
 * A page's first screenful arriving in a sweep: [STEP_MS] apart, never longer than [CAP_MS] in all,
 * opacity only (the page body already rises — see PageArrival.kt), over MotionToken.ENTER.
 *
 * ⛔ Viewport-scoped. Only what [isOnScreen] says the reader can see is touched; everything below the
 * fold renders plainly, so a 1,204-book grid costs the same as a 28-book one.
 *
 * ⛔ Only on a forward arrival. A container staggers when it mounts within [ARRIVAL_WINDOW_MS] of a
 * link landing on its page ([markPageArrival]) — not on Back (a place already seen comes back
 * instantly, even when it is Back within that window of a link), not on a sort or filter, and never
 * as a virtual list recycles rows while scrolling. CSS
 * cannot tell arrival from recycling, which is why this is not a CSS `animation`: measured, one on the
 * grid's cards replayed 149 times across 2,400px of scroll.
 */

private var arrivalAt: Double = Double.NEGATIVE_INFINITY

/** Marks that a link has just landed on a new page. */
internal fun markPageArrival(now: Double = window.performance.now()) {
    arrivalAt = now
}

/**
 * Whether a page arrival is recent enough that what mounts now is part of it — and the route has not
 * since moved Back, which would otherwise sweep in the page returned to, at the top, before its
 * place was restored.
 */
internal fun isArriving(now: Double = window.performance.now()): Boolean =
    lastRouteChange() != RouteChange.POP && now - arrivalAt <= ARRIVAL_WINDOW_MS

/** Specs only. */
internal fun forgetPageArrival() {
    arrivalAt = Double.NEGATIVE_INFINITY
}

/**
 * The delay for each of [count] items: [STEP_MS] apart, compressed when that would run past [CAP_MS],
 * so a long screenful still sweeps evenly rather than arriving in one block at the cap.
 */
internal fun staggerDelays(count: Int): List<Double> {
    if (count <= 0) return emptyList()
    val step = if (count == 1) STEP_MS else minOf(STEP_MS, CAP_MS / (count - 1))
    return List(count) { it * step }
}

/**
 * Staggers [container]'s children in, if a page is arriving.
 *
 * Deferred to a microtask: a container's `ref` fires mid-apply, before its page has finished
 * mounting (and before [PageArrival] has marked the arrival). The microtask runs once the apply is
 * done — the DOM attached and laid out — and still before the frame paints, so nothing flashes.
 */
internal fun staggerOnArrival(container: Element) {
    Promise.resolve(Unit).then { if (isArriving()) staggerIn(container) }
}

/** Opts this container's children into the arrival stagger. */
internal fun AttrsScope<*>.staggerChildrenOnArrival() {
    ref { element ->
        staggerOnArrival(element)
        onDispose { }
    }
}

/**
 * Staggers the on-screen children of [container] in now.
 *
 * A second call on the same container replaces the first sweep rather than adding to it: a
 * `VirtualList` sweeps its first paint, then sweeps again over the fuller screenful it mounts once
 * it has measured itself, and that has to read as one sweep from the top, not two overlapping.
 */
internal fun staggerIn(container: Element) {
    sweeps.get(container)?.forEach { it.cancel() }
    val targets =
        container.children
            .asList()
            .mapNotNull(::staggerTargetOf)
            .filter { isOnScreen(it) && !isLanding(it) }
    val motions =
        staggerDelays(targets.size).zip(targets).map { (delay, target) ->
            animateComposited(
                target,
                listOf(Keyframe.opacity(0.0), Keyframe.opacity(1.0)),
                MotionToken.ENTER,
                delayMs = delay,
                fill = MotionFill.BACKWARDS,
            )
        }
    sweeps.set(container, motions)
}

/** Each container's latest sweep, held only as long as the container itself is. */
private val sweeps = WeakMap<Element, List<Motion>>()

/** The browser's `WeakMap`, as much of it as the sweep uses. */
private external class WeakMap<K : Any, V : Any> {
    fun get(key: K): V?

    fun set(
        key: K,
        value: V,
    )
}

/**
 * The element that actually draws [child]: a `VirtualList` item wrapper is `display:contents` (no box
 * to fade), so its card is the target; a spacer stands in for rows that are not there.
 */
private fun staggerTargetOf(child: Element): Element? =
    when {
        child.classList.contains("vl-spacer") -> null
        child.classList.contains("vl-item") -> child.firstElementChild
        else -> child
    }

private const val STEP_MS = 20.0

private const val CAP_MS = 200.0

/** Long enough for a page's first local read to land; short enough that a later reload never sweeps. */
private const val ARRIVAL_WINDOW_MS = 600.0
