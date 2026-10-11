package com.calypsan.listenup.web.features.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.SEARCH_QUERY_KEY
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.nav.seeAllType
import com.calypsan.listenup.web.nav.slug
import com.calypsan.listenup.web.shell.RouteContent
import com.calypsan.listenup.web.shell.SEARCH_OPENABLE_TYPES
import com.calypsan.listenup.web.shell.searchNavRoute

/**
 * The `/search` branch of [RouteContent]: the search page, and the single-type page its capped
 * groups defer to.
 *
 * ⛔ The see-all is tested first. `/search` is a prefix of `/search/{type}`, so a branch order that
 * asks "is this the search page?" first makes every see-all link resolve to the page it was meant
 * to expand — the same trap the series and book forms sit behind.
 */
@Composable
internal fun SearchRouteContent(
    router: Router,
    route: Route,
    openSearch: OpenSearch,
    openSeeAll: OpenSeeAll,
) {
    val seeAllType = route.seeAllType()
    if (seeAllType != null) {
        SeeAllRoute(
            router = router,
            openSeeAll = openSeeAll,
            query = route.query[SEARCH_QUERY_KEY].orEmpty(),
            type = seeAllType,
        )
        return
    }

    SearchRoute(
        router = router,
        route = route,
        openSearch = openSearch,
        onSeeAll = { type -> router.navigate(Routes.searchType(type.slug(), route.query)) },
    )
}

/**
 * Opens a see-all session and collects it.
 *
 * Keyed on the query *and* the type: `/search/books?q=dune` → `/search/people?q=dune` changes only
 * the type, and a session remembered on the query alone would keep answering with books.
 */
@Composable
private fun SeeAllRoute(
    router: Router,
    openSeeAll: OpenSeeAll,
    query: String,
    type: SearchHitType,
) {
    val session = rememberSession(query, type) { openSeeAll(query, type) }

    LaunchedEffect(session) {
        session.navActions.collect { action -> router.navigate(searchNavRoute(action)) }
    }

    SeeAllPage(
        state = session.state.collectAsState().value,
        openableTypes = SEARCH_OPENABLE_TYPES,
        onOpenHit = session.onOpenHit,
        onOpenSearch = { router.navigate(Routes.searchFor(query)) },
    )
}

/**
 * The `/search` branch of [RouteContent] — session lifecycle, the two-way `?q=` binding with the
 * address bar, and hit navigation. Extracted so [RouteContent] stays a single glance of routing
 * rather than a second page's worth of effects folded into one branch.
 *
 * The `?q=` binding runs in both directions. URL → session: a shared `/search?q=dune` link (or a
 * Back/Forward press) seeds the field, via a [LaunchedEffect] keyed on the URL's own `q`. Session →
 * URL: typing calls [Router.replace] synchronously from the same callback that tells the session
 * about the change — not a second effect watching [SearchSession.state] — so the URL never lags a
 * keystroke behind. Either way it's [Router.replace], never [Router.navigate]: one history entry
 * for the whole visit, not one per keystroke, so Back leaves the search rather than replaying it
 * letter by letter.
 *
 * Every hit type has somewhere to go now — see [searchNavRoute]. [SearchPage] is still told which
 * types are openable ([SEARCH_OPENABLE_TYPES]) rather than assuming all of them, because that set
 * is what keeps a row's pressability and its destination the same fact.
 */
@Composable
internal fun SearchRoute(
    router: Router,
    route: Route,
    openSearch: OpenSearch,
    onSeeAll: (SearchHitType) -> Unit,
) {
    val session = rememberSession { openSearch() }

    // URL -> session: seed (and re-seed on Back/Forward) the query the address bar names.
    LaunchedEffect(session, route.query[SEARCH_QUERY_KEY]) {
        val urlQuery = route.query[SEARCH_QUERY_KEY].orEmpty()
        if (urlQuery != session.state.value.query) session.onQueryChanged(urlQuery)
    }

    LaunchedEffect(session) {
        session.navActions.collect { action -> router.navigate(searchNavRoute(action)) }
    }

    SearchPage(
        state = session.state.collectAsState().value,
        onQueryChanged = { query -> onSearchFieldChanged(query, session, router) },
        onToggleType = session.onToggleType,
        onClearTypes = session.onClearTypes,
        onOpenHit = session.onOpenHit,
        onSeeAll = onSeeAll,
        onRetry = session.retry,
        openableTypes = SEARCH_OPENABLE_TYPES,
    )
}

/** session -> URL, the other half of [SearchRoute]'s two-way `?q=` binding — see its KDoc. */
private fun onSearchFieldChanged(
    query: String,
    session: SearchSession,
    router: Router,
) {
    session.onQueryChanged(query)
    val urlQuery = router.current.query[SEARCH_QUERY_KEY].orEmpty()
    if (urlQuery == query) return
    val newQuery =
        if (query.isBlank()) {
            router.current.query - SEARCH_QUERY_KEY
        } else {
            router.current.query +
                (SEARCH_QUERY_KEY to query)
        }
    router.replace(Route(router.current.segments, newQuery))
}
