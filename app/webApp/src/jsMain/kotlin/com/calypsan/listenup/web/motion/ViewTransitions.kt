package com.calypsan.listenup.web.motion

import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.Promise

/**
 * Runs [apply] inside a browser View Transition, so the change it makes is animated rather than
 * swapped in.
 *
 * ⚠️ **The callback must not return until the DOM has actually changed.** `startViewTransition`
 * takes its "after" snapshot the moment the callback settles, but Compose applies recomposition
 * asynchronously — so returning immediately captures the *old* DOM and animates nothing. The
 * promise below resolves after two animation frames, which is when Compose has flushed.
 *
 * ⛔ **Never name a long list.** The browser snapshots every element carrying a
 * `view-transition-name`, and that cost is what decides whether this API is usable at all.
 * Measured against the 1,204-book library: naming every card took **12.2 s** to reach
 * `ready`; naming only the ~28 in the viewport took **41 ms**. Assigning the names is free
 * (3 ms for all 1,204) — the collapse is entirely in snapshotting. See [withScopedNames].
 *
 * Degrades to a plain call when the browser lacks the API (Firefox at time of writing) or the
 * reader asked for less motion. Both paths still apply the change; only the animation is optional.
 */
internal fun withViewTransition(
    scopeSelector: String? = null,
    prefix: String = "vt",
    apply: () -> Unit,
) {
    if (!viewTransitionsUsable()) {
        apply()
        return
    }
    // Named BEFORE the call: `startViewTransition` captures the "old" snapshot synchronously, so an
    // element unnamed at this moment is not in it.
    val named = if (scopeSelector == null) emptyList() else nameVisible(scopeSelector, prefix)

    // Applied BEFORE the transition starts, deliberately. `startViewTransition` invokes its
    // callback asynchronously, so applying in there would make every caller's state write async
    // too — `Router.navigate` would return without having updated `current`, breaking a contract
    // its own tests assert. Compose has not flushed the DOM by the next line either, so the "old"
    // snapshot is still the pre-change page; the callback only has to wait for the flush.
    apply()
    val transition =
        document.asDynamic().startViewTransition({
            Promise<Unit> { resolve, _ ->
                // Two frames is when Compose has normally flushed — but a `requestAnimationFrame`
                // queues BEHIND the recomposition, so on an expensive page "two frames" is however
                // long that takes. Leaving the 1,204-book grid, that measured **4,064 ms**, held as
                // a frozen snapshot of the page the reader just left.
                //
                // ⛔ A transition must never cost more than not having one. The timeout is the
                // ceiling on that: past it the transition proceeds regardless, and the worst case
                // is that it animates nothing and the change lands plainly — exactly the behaviour
                // of a browser without the API.
                var settled = false
                val settle = {
                    if (!settled) {
                        settled = true
                        resolve(Unit)
                    }
                }
                window.requestAnimationFrame {
                    window.requestAnimationFrame { settle() }
                }
                window.setTimeout({ settle() }, MAX_WAIT_MS)
            }
        })
    // Cleared only once the transition is over — NOT in a `finally` around the call above, which
    // would run while the animation was still going and drop the elements out of the "new"
    // snapshot. `finished` rejects on a skipped transition, so both paths clear.
    val clear = { named.forEach { it.style.viewTransitionName = "" } }
    transition.finished.then({ clear() }, { clear() })
}

/**
 * Names every element matching [selector] that is within [MARGIN_PX] of the viewport, and returns
 * them so the caller can clear the names when it is done.
 *
 * The scoping is the whole point — see the measurement on [withViewTransition]. An element the
 * reader cannot see does not need to glide, and paying to snapshot it is what makes this API
 * unusable on a real library.
 */
private fun nameVisible(
    selector: String,
    prefix: String,
): List<dynamic> {
    val named = mutableListOf<dynamic>()
    val nodes = document.querySelectorAll(selector)
    val viewportHeight = window.innerHeight
    for (index in 0 until nodes.length) {
        val node = nodes.item(index).asDynamic()
        val rect = node.getBoundingClientRect()
        val bottom: Double = rect.bottom
        val top: Double = rect.top
        val margin: Double = MARGIN_PX.toDouble()
        val limit: Double = viewportHeight.toDouble() + margin
        if (bottom > -margin && top < limit) {
            node.style.viewTransitionName = "$prefix${named.size}"
            named.add(node)
        }
    }
    return named
}

/** False when the API is absent, or when the reader has asked for reduced motion. */
private fun viewTransitionsUsable(): Boolean {
    val hasApi = document.asDynamic().startViewTransition != null
    if (!(hasApi as Boolean)) return false
    return !window.matchMedia("(prefers-reduced-motion: reduce)").matches
}

/** Ceiling on how long a transition may wait for the DOM to settle — see the note in the callback. */
private const val MAX_WAIT_MS = 200

/** How far outside the viewport still counts as worth animating, in CSS pixels. */
private const val MARGIN_PX = 200
