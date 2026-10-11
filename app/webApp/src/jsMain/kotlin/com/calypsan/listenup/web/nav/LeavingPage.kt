package com.calypsan.listenup.web.nav

import com.calypsan.listenup.web.motion.captureHeroOriginBeforeRouteChange

/**
 * The router's hook: everything that has to read the page being left while it is still laid out.
 *
 * Compose renders on a later frame than the route change, so this — called before `current` moves —
 * is the last moment the outgoing page can be measured. Its scroll offset is what Back restores; its
 * hero's position is where the cover flies home from.
 */
internal fun readLeavingPage(change: RouteChange) {
    captureScrollBeforeRouteChange(change)
    captureHeroOriginBeforeRouteChange()
}
