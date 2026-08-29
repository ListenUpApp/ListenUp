package com.calypsan.listenup.web

import com.calypsan.listenup.web.nav.Route

/**
 * The query key an invite link lands on: `https://your-server/?invite=CODE`.
 *
 * A path (`/invite/CODE`) was the other option and is the worse one here. Paths are what [Route]
 * routes on, and an invite is not a page — giving it a segment would put a screen identity in the
 * URL for a state only `AuthState` may decide, which is the exact rule
 * [com.calypsan.listenup.web.features.auth.AuthGate] exists to hold. A query is a payload you
 * arrive carrying, which is what a code actually is.
 */
internal const val INVITE_QUERY_KEY = "invite"

/**
 * Splits an arriving invite code off [route], returning it with the route that should replace it.
 *
 * The code is removed rather than left in the address bar, and that is a deliberate call rather
 * than tidiness. An invite code is a one-time credential: left in the URL it persists in history,
 * in a copied link, in a shared screenshot — and the reader who would share it is exactly the one
 * who just used it and no longer needs it. Nothing downstream wants it back either, since the
 * ViewModel holds the code for the whole flow.
 *
 * Pure, so the rule is provable without touching `window.history`: every other query parameter and
 * every path segment survives, which is the part that would break silently.
 */
internal fun takeInviteCode(route: Route): Pair<String?, Route> {
    val code = route.query[INVITE_QUERY_KEY]?.takeIf { it.isNotBlank() }
    if (code == null) return null to route
    return code to Route(route.segments, route.query - INVITE_QUERY_KEY)
}
