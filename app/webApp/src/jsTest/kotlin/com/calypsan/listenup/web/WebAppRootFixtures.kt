package com.calypsan.listenup.web

import com.calypsan.listenup.web.features.admin.fixedAdmin
import com.calypsan.listenup.web.features.devices.fixedDevices
import com.calypsan.listenup.web.features.settings.OpenSettings
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.web.features.shelf.fixedShelfDetail
import com.calypsan.listenup.web.features.shelf.fixedShelfEdit
import com.calypsan.listenup.web.features.discover.fixedDiscover
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.bookdetail.OpenBookDetail
import com.calypsan.listenup.web.features.bookdetail.fixedBookDetail
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.contributors.OpenContributors
import com.calypsan.listenup.web.features.contributors.fixedContributors
import com.calypsan.listenup.web.features.home.OpenHome
import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback
import com.calypsan.listenup.web.features.search.OpenSearch
import com.calypsan.listenup.web.features.search.SearchSession
import com.calypsan.listenup.web.features.search.fixedSearch
import com.calypsan.listenup.web.nav.Router
import androidx.compose.runtime.Composition
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.web.features.contributordetail.OpenContributorDetail
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.web.features.contributors.ContributorsSession
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailSession
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.web.features.seriesdetail.OpenSeriesDetail
import com.calypsan.listenup.web.features.seriesdetail.SeriesDetailSession
import com.calypsan.listenup.web.features.seriesdetail.fixedSeriesDetail
import com.calypsan.listenup.web.features.seriesdetail.readySeries
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.web.features.notifications.OpenNotificationBell
import com.calypsan.listenup.web.features.notifications.OpenNotifications
import com.calypsan.listenup.web.features.notifications.fixedNotificationBell
import com.calypsan.listenup.web.features.notifications.fixedNotifications
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.web.features.notifications.OpenNotificationPrefs
import com.calypsan.listenup.web.features.notifications.fixedNotificationPrefs

/**
 * Shared root-wiring test rig — mounts the real [WebAppRoot] behind a real [Router], for any spec
 * that needs to prove keyboard, mouse or URL behaviour against the whole shell rather than one
 * page in isolation. Pulled out of [WebAppRootTest] once [CommandPaletteTest] needed the exact
 * same rig for a second file, the same call `BookDetailFixtures.kt` makes for its own feature.
 *
 * Returns the [Composition] too — [WebAppRoot] now mounts a `window`-level `keydown` listener for
 * its command palette (see `CommandPaletteHost`), and that listener outlives the test unless the
 * composition is explicitly disposed. Undisposed, it keeps answering every later test's synthetic
 * keydowns from a detached, orphaned tree — a stale palette silently reacting to a DIFFERENT
 * test's keystrokes and calling ITS OWN router's `navigate()`, which mutates the one real
 * `window.location` every test shares. Every caller must `composition.dispose()` in its `finally`,
 * alongside `router.dispose()`.
 */
internal fun mountAt(
    path: String,
    isAdmin: Flow<Boolean> = flowOf(false),
    openBookDetail: OpenBookDetail = fixedBookDetail(readyBook()),
    openContributorDetail: OpenContributorDetail = fixedContributorDetail(ContributorDetailUiState.Loading),
    openSeriesDetail: OpenSeriesDetail = fixedSeriesDetail(SeriesDetailUiState.Loading),
    openNotifications: OpenNotifications = fixedNotifications(NotificationsUiState.Empty),
    openNotificationPrefs: OpenNotificationPrefs = fixedNotificationPrefs(NotificationPrefsUiState.Loading),
    openNotificationBell: OpenNotificationBell = fixedNotificationBell(),
    openContributors: OpenContributors = fixedContributors(emptyList()),
    openHome: OpenHome = fixedHome(HomeUiState.Loading),
    openLibrary: OpenLibrary = fakeLibrary(),
    openSettings: OpenSettings = fixedSettings(),
    openSearch: OpenSearch = fixedSearch(SearchUiState.Idle()),
): Triple<HTMLElement, Router, Composition> {
    window.history.replaceState(null, "", path)
    val router = Router()
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    val composition =
        renderComposable(root = host) {
            WebAppRoot(
                router,
                openBookDetail,
                fixedBookEdit(BookEditUiState()),
                openContributorDetail,
                openSeriesDetail,
                openNotifications,
                openNotificationPrefs,
                openContributors,
                openHome,
                fixedDiscover(),
                openSettings,
                fixedDevices(),
                fixedAdmin(),
                fixedShelfDetail(),
                fixedShelfEdit(),
                openLibrary,
                openSearch,
                openNotificationBell,
                fixedPlayback(),
                observeIsAdmin = { isAdmin },
            )
        }
    return Triple(host, router, composition)
}

/**
 * A Search session whose state genuinely reacts to [onQueryChanged] — synchronously and without
 * the real ViewModel's debounce or FTS call, so a spec about a `?q=` URL seam (or the palette's own
 * query field) does not need a database behind it. Always [SearchUiState.Idle]; these specs assert
 * only on `.query`, never on which phase renders — that contract already belongs to
 * `SearchPageTest`.
 */
internal fun reactiveSearch(): OpenSearch {
    val state = MutableStateFlow<SearchUiState>(SearchUiState.Idle())
    return {
        SearchSession(
            state = state,
            onQueryChanged = { query -> state.value = SearchUiState.Idle(query = query) },
            onToggleType = {},
            onOpenHit = {},
            retry = {},
            navActions = emptyFlow(),
            close = {},
        )
    }
}

/**
 * A Search session whose [SearchUiState.Results] is fixed but whose hit clicks genuinely emit
 * [SearchNavAction]s — enough to prove [WebAppRoot] (and the command palette it hosts) route them
 * without a real ViewModel or FTS index behind it.
 */
internal fun hitNavigatingSearch(result: SearchResult): OpenSearch {
    val navChannel = Channel<SearchNavAction>(Channel.BUFFERED)
    return {
        SearchSession(
            state =
                MutableStateFlow(
                    SearchUiState.Results(query = result.query, selectedTypes = emptySet(), result = result),
                ),
            onQueryChanged = {},
            onToggleType = {},
            onOpenHit = { hit -> navChannel.trySend(navActionFor(hit)) },
            retry = {},
            navActions = navChannel.receiveAsFlow(),
            close = {},
        )
    }
}

/** Mirrors [com.calypsan.listenup.client.presentation.search.SearchViewModel.onResultSelected]'s
 *  own id+type mapping — this fixture only needs to prove the mapping reaches the router. */
internal fun navActionFor(hit: SearchHit): SearchNavAction =
    when (hit.type) {
        SearchHitType.BOOK -> SearchNavAction.NavigateToBook(hit.id)
        SearchHitType.CONTRIBUTOR -> SearchNavAction.NavigateToContributor(hit.id)
        SearchHitType.SERIES -> SearchNavAction.NavigateToSeries(hit.id)
        SearchHitType.TAG -> SearchNavAction.NavigateToTag(hit.id, hit.name)
    }

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
internal suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}

/** How long a spec waits for a state-flow value to reach the DOM. */
internal const val RECOMPOSE_TIMEOUT_MS = 2_000L

/**
 * Waits until [selector] matches nothing under [host].
 *
 * ⛔ Wait for the DOM condition you are about to assert — never for a proxy of it. A navigation
 * flips `window.location` synchronously, but the recomposition that REMOVES the old page's markup
 * lands a frame later. A spec that waits on the URL and then asserts the DOM is asserting one frame
 * early: reliably green on a fast machine, and intermittently red on a two-core CI runner, which is
 * the worst possible place to find out.
 */
internal suspend fun awaitGone(
    host: HTMLElement,
    selector: String,
) {
    withTimeout(RECOMPOSE_TIMEOUT_MS) {
        while (host.querySelector(selector) != null) delay(FRAME_POLL_MS)
    }
}

private const val FRAME_POLL_MS = 10L

/** An [OpenContributors] that records every role it was asked to open, in the order asked. */
internal class RecordingContributors {
    val requestedRoles = mutableListOf<ContributorRole>()
    val open: OpenContributors = { role ->
        requestedRoles += role
        ContributorsSession(state = MutableStateFlow(emptyList()), close = {})
    }
}

/** An [OpenSeriesDetail] that records every id it was asked to open, in the order asked. */
internal class RecordingSeriesDetail {
    val requestedIds = mutableListOf<String>()
    val open: OpenSeriesDetail = { id ->
        requestedIds += id
        SeriesDetailSession(
            state = MutableStateFlow(readySeries(seriesId = id, seriesName = "Series $id")),
            close = {},
        )
    }
}

/** An [OpenContributorDetail] that records every id it was asked to open, in the order asked. */
internal class RecordingContributorDetail {
    val requestedIds = mutableListOf<String>()
    val open: OpenContributorDetail = { id ->
        requestedIds += id
        ContributorDetailSession(
            state = MutableStateFlow(readyContributor(name = "Contributor $id")),
            close = {},
        )
    }
}
