package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.search.CommandPalette
import com.calypsan.listenup.web.features.search.OpenSearch
import com.calypsan.listenup.web.features.search.SearchRoute
import com.calypsan.listenup.web.features.search.SearchSession
import com.calypsan.listenup.web.features.search.openableSearchHits
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.SEARCH_QUERY_KEY
import com.calypsan.listenup.web.nav.slug
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * The ⌘K / Ctrl+K / `/` command palette's lifecycle, wiring and single keyboard listener.
 *
 * Mounted once at the shell's root, like [playbackState], so the shortcut works from every route
 * — not just `/search`. Opens its OWN [SearchSession] on each open and closes it on each close,
 * never the one [SearchRoute] holds: the palette is a transient jump tool, and sharing a session
 * would mean either leaking one across every open/close cycle a page never tore down, or fighting
 * the `/search` route over whose query wins. A fresh session every time also means the palette
 * always starts at [SearchUiState.Idle] — the same "start clean" contract [bookDetailState] gives
 * a fresh book visit.
 *
 * All of this palette's keyboard behaviour — opening, arrow navigation, Enter, Shift+Enter, Escape
 * and the focus trap — lives in ONE `window`-level `keydown` listener, registered for the shell's
 * whole lifetime rather than only while the palette is open. That is what lets `/` and ⌘K work
 * from anywhere: a listener scoped to the palette's own composition cannot hear a keystroke fired
 * before the palette exists. Routing every in-palette key through that same listener — rather than
 * a second one attached once the panel mounts — is also what makes the "Tab cannot escape" trap
 * trivial to get right: Tab is simply intercepted and dropped for as long as [isOpen][Boolean] is
 * true, so focus never has anywhere else to go, regardless of what element happens to hold it.
 *
 * Focus discipline: the moment the shortcut fires, `document.activeElement` is captured into
 * `restoreFocusTo` — before [CommandPalette] mounts and steals it — so dismissing the palette
 * (Escape, or the dialog's own close request) hands focus back to the exact control the reader was
 * on. A close that goes somewhere (a hit, See all, the Shift+Enter commit) does not: the page it
 * lands on takes focus on its heading instead, the way every other navigation does.
 */
@Composable
internal fun CommandPaletteHost(
    router: Router,
    openSearch: OpenSearch,
) {
    var isOpen by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<SearchSession?>(null) }
    var highlighted by remember { mutableStateOf<SearchHit?>(null) }
    var restoreFocusTo by remember { mutableStateOf<HTMLElement?>(null) }
    var returnFocusTo by remember { mutableStateOf<HTMLElement?>(null) }

    // [returnFocus] false when the close is a journey somewhere else: the new page takes focus
    // itself (see FocusPageOnNavigation), and handing it back to a control on the page being left
    // would only pull it away from there again.
    fun closePalette(returnFocus: Boolean = true) {
        isOpen = false
        session?.close()
        session = null
        highlighted = null
        if (returnFocus) returnFocusTo = restoreFocusTo
        restoreFocusTo = null
    }

    fun openPalette() {
        restoreFocusTo = document.activeElement as? HTMLElement
        val newSession = openSearch()
        session = newSession
        // Computed synchronously from the fresh session's own StateFlow.value, not left for the
        // LaunchedEffect below to fill in — that effect only runs on the composition's next pass,
        // one recompose later than this. A session opened via [fixedSearch]/[hitNavigatingSearch]
        // already carries its Results on open, so waiting for the effect meant the very first
        // frame rendered with no row highlighted at all.
        highlighted = openableSearchHits(newSession.state.value, SEARCH_OPENABLE_TYPES).firstOrNull()
        isOpen = true
    }

    fun moveHighlight(delta: Int) {
        val hits = openableSearchHits(session?.state?.value ?: return, SEARCH_OPENABLE_TYPES)
        if (hits.isEmpty()) {
            highlighted = null
            return
        }
        val currentIndex = hits.indexOf(highlighted).let { if (it < 0) 0 else it }
        highlighted = hits[(currentIndex + delta).mod(hits.size)]
    }

    WindowKeyHandler(
        KeyPriority.Palette,
        paletteKeyDownHandler(
            isOpen = { isOpen },
            onOpen = ::openPalette,
            onClose = { closePalette() },
            onMoveHighlight = ::moveHighlight,
            onActivateHighlighted = {
                session?.let { activeSession -> highlighted?.let(activeSession.onOpenHit) }
            },
            onCommit = {
                session?.let { activeSession ->
                    val query = activeSession.state.value.query
                    closePalette(returnFocus = false)
                    router.navigate(paletteSearchRoute(query))
                }
            },
        ),
    )

    // Handed back once the palette has actually left the page, not in the close call itself: until
    // the recomposition that removes it, the palette is a modal `<dialog>` and everything behind it
    // is inert, so focusing the invoker straight away is silently refused.
    returnFocusTo?.let { invoker ->
        LaunchedEffect(invoker) {
            invoker.focus()
            returnFocusTo = null
        }
    }

    val activeSession = session
    if (isOpen && activeSession != null) {
        LaunchedEffect(activeSession) {
            activeSession.navActions.collect { action ->
                closePalette(returnFocus = false)
                router.navigate(searchNavRoute(action))
            }
        }

        val uiState = activeSession.state.collectAsState().value
        // Keeps the highlight valid as results arrive: resets to the first openable hit whenever
        // the candidate list changes, so a stale highlight from the previous query never survives
        // into a new result set, and clears it when nothing is openable at all.
        LaunchedEffect(activeSession, uiState) {
            val hits = openableSearchHits(uiState, SEARCH_OPENABLE_TYPES)
            if (highlighted !in hits) highlighted = hits.firstOrNull()
        }

        CommandPalette(
            state = uiState,
            onQueryChanged = activeSession.onQueryChanged,
            onOpenHit = activeSession.onOpenHit,
            openableTypes = SEARCH_OPENABLE_TYPES,
            highlighted = highlighted,
            // Escape reaches the host's own key handler first, but a close request the page never
            // sees as a key (the platform back gesture) arrives here, through the dialog itself.
            onDismiss = { closePalette() },
            // ⛔ The palette caps its groups like the page does, so it owes the same way out. Shift
            // +Enter reaches the whole search, but a reader looking at "4 of 26 books" wants those
            // 26, not a fresh query they have to narrow again.
            onSeeAll = { type ->
                val query = uiState.query
                closePalette(returnFocus = false)
                router.navigate(Routes.searchType(type.slug(), mapOf(SEARCH_QUERY_KEY to query)))
            },
        )
    }
}

/**
 * Builds [CommandPaletteHost]'s key handler for the shell's [WindowKeys] from the callbacks it
 * needs invoked; true when it took the press. Pulled out of the composable itself so its own branching doesn't count against
 * [CommandPaletteHost]'s cognitive/cyclomatic complexity — this is a plain dispatch table, not
 * state the composable needs to reason about.
 */
private fun paletteKeyDownHandler(
    isOpen: () -> Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMoveHighlight: (Int) -> Unit,
    onActivateHighlighted: () -> Unit,
    onCommit: () -> Unit,
): (KeyboardEvent) -> Boolean =
    handler@{ keyboardEvent ->
        // Enter, an arrow or Escape while an input method is composing belongs to the composition
        // (confirming a candidate, picking one), not to the palette.
        if (keyboardEvent.isComposing) return@handler false
        if (!isOpen()) {
            val isShortcut =
                keyboardEvent.key.equals(PALETTE_SHORTCUT_KEY, ignoreCase = true) &&
                    (keyboardEvent.metaKey || keyboardEvent.ctrlKey)
            val isSlash = keyboardEvent.key == "/" && !isEditableTarget(document.activeElement)
            // Another modal — a confirm dialog — makes the page behind it inert, and the shortcut
            // belongs to that page. Opening over it would stack two modals.
            if ((!isShortcut && !isSlash) || anotherModalIsOpen()) return@handler false
            keyboardEvent.preventDefault()
            onOpen()
            return@handler true
        }
        val action: (() -> Unit)? =
            when (keyboardEvent.key) {
                "Escape" -> onClose
                "ArrowDown" -> ({ onMoveHighlight(1) })
                "ArrowUp" -> ({ onMoveHighlight(-1) })
                "Enter" -> if (keyboardEvent.shiftKey) onCommit else onActivateHighlighted
                else -> null
            }
        action?.let {
            keyboardEvent.preventDefault()
            it()
        }
        action != null
    }

/**
 * Where a search hit leads — the one table both the `/search` page and the command palette read,
 * so a hit type cannot be openable on one and inert on the other.
 *
 * ⛔ Every arm navigates now. While `NavigateToSeries`/`NavigateToTag` fell into a `Unit` arm here,
 * [SEARCH_OPENABLE_TYPES] was the thing keeping their rows honestly non-interactive — and the
 * palette's own contributor arm had quietly drifted out of that agreement, so a contributor hit
 * there rendered pressable and answered nothing.
 */
internal fun searchNavRoute(action: SearchNavAction): Route =
    when (action) {
        is SearchNavAction.NavigateToBook -> Routes.Book.detail(action.bookId)
        is SearchNavAction.NavigateToContributor -> Routes.Contributor.detail(action.contributorId)
        is SearchNavAction.NavigateToSeries -> Routes.series(action.seriesId)
        is SearchNavAction.NavigateToTag -> Routes.tag(action.tagId)
    }

/** Where the palette's Shift+Enter commits: `/search`, or `/search?q=…` for a non-blank query. */
private fun paletteSearchRoute(query: String): Route = if (query.isBlank()) Routes.search else Routes.searchFor(query)

/** True while a modal `<dialog>` is showing. Asked only while the palette is shut, so it is never the palette. */
private fun anotherModalIsOpen(): Boolean = document.querySelector("dialog:modal") != null

/** The letter half of the ⌘K / Ctrl+K shortcut. */
private const val PALETTE_SHORTCUT_KEY = "k"

/**
 * Hit types with a real place to navigate to — all of them, now that `/series/{id}` and `/tag/{id}`
 * both exist. Kept as a named set rather than inlined as "everything": [searchNavRoute] is what
 * makes the claim true, and the day a fifth hit type arrives without a route, this is where it is
 * excluded from.
 */
internal val SEARCH_OPENABLE_TYPES = SearchHitType.entries.toSet()
