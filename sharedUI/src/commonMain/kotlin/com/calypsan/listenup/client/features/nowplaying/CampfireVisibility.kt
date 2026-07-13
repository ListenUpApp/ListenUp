package com.calypsan.listenup.client.features.nowplaying

/**
 * Whether the full-screen slide-up container should be visible — solo player *or* campfire flow.
 *
 * Extracted as a pure predicate so the "minimize" decoupling is unit-tested without a device
 * (co-listening coexistence spec, B1). A live campfire ([hasSession]) takes over full-screen unless
 * [minimized]; independently, the solo player shows when [isExpanded] with an active book. The two
 * terms are OR-ed: minimizing a campfire never hides a solo player the user expanded on purpose.
 */
fun campfireFullScreenVisible(
    isExpanded: Boolean,
    hasActiveBook: Boolean,
    hasSession: Boolean,
    minimized: Boolean,
): Boolean = (isExpanded && hasActiveBook) || (hasSession && !minimized)

/**
 * Whether the full-screen campfire flow (lobby/room) — as opposed to the solo player — should fill
 * the visible container. A minimized campfire yields the solo player instead: tapping the mini-bar
 * (which expands the container) while minimized must NOT drop the user into the room with the
 * collapse gesture disabled (co-listening coexistence spec, B1 — Never Stranded).
 */
fun campfireFlowShown(
    hasSession: Boolean,
    hasBook: Boolean,
    minimized: Boolean,
): Boolean = hasSession && hasBook && !minimized
