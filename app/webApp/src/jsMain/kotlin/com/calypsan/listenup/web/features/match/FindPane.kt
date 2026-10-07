package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.PartialFailure
import com.calypsan.listenup.client.presentation.match.RegionUi
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PopupMenu
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.focusLanding
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * The Find pane (W-01, W-05): the search, the store, Your copy, and the matches in their two groups —
 * or why there are none and what to do about it.
 *
 * A search in flight keeps the last results on screen, marked busy, so nothing jumps to blank. Each
 * row is one `<button>` that opens its Review; [onPick] does the moving of focus.
 */
@Composable
internal fun FindPane(
    find: FindUiState,
    bookId: String,
    session: BookMatchSession,
    onPick: (CandidateUi) -> Unit,
    onOpenCompare: () -> Unit,
) {
    val shown: FindUiState.Results? =
        when (find) {
            is FindUiState.Results -> find
            is FindUiState.Searching -> find.previous
            is FindUiState.Failed -> null
        }
    val region: RegionUi? =
        when (find) {
            is FindUiState.Results -> find.region
            is FindUiState.Searching -> find.previous?.region
            is FindUiState.Failed -> find.region
        }

    Div(attrs = { classes("bmx-pane-h") }) {
        H2(attrs = {
            classes("bmx-pane-t")
            attr("id", FIND_HEADING_ID)
            attr("tabindex", "-1")
        }) { Text("Find") }
        shown?.let { Span(attrs = { classes("bmx-count") }) { Text(resultCountText(it.all.size)) } }
        Span(attrs = { classes("bmx-grow") })
        if (shown != null && shown.all.isNotEmpty()) {
            Button(kind = ButtonKind.Secondary, size = ButtonSize.Md, onClick = onOpenCompare, attrs = {
                attr("id", COMPARE_OPEN_ID)
            }) { Text(COMPARE) }
        }
    }

    find.yourCopy?.let { YourCopyStrip(it, bookId) }

    SearchForm(
        query = queryOf(find),
        onSearch = session.search,
        region = region,
        onChooseStore = session.chooseStoreForThisSearch,
    )

    if (find is FindUiState.Searching) {
        P(attrs = {
            classes("bmx-searching")
            attr("aria-hidden", "true")
        }) {
            Icon(WebIcon.Clock, size = SMALL_ICON)
            Text(SEARCHING)
        }
        if (shown == null) Div(attrs = { classes("skel", "bmx-skel") })
    }

    shown?.steps?.let(::stepsText)?.let { P(attrs = { classes("bmx-note") }) { Text(it) } }
    shown?.partialFailure?.let { PartialBanner(it, session.retry) }
    shown?.let { results ->
        val busy = find is FindUiState.Searching
        CandidateGroup("Strong match", results.strong, results, busy, onPick)
        CandidateGroup("Maybe", results.maybe, results, busy, onPick)
    }

    if (find is FindUiState.Failed) FailureCard(find.failure, session)
}

/** "Your copy": the cover, length · narrator · chapters — read from this device, never blank. */
@Composable
private fun YourCopyStrip(
    copy: YourCopyUi,
    bookId: String,
) {
    Div(attrs = { classes("bmx-copy") }) {
        Art(url = coverUrl(bookId, copy.coverHash, width = ART_WIDTH))
        Div(attrs = { classes("bmx-copy-text") }) {
            Span(attrs = { classes("bmx-copy-l") }) { Text("Your copy") }
            yourCopyMetaText(
                copy,
            ).takeIf { it.isNotBlank() }?.let { Span(attrs = { classes("bmx-copy-m") }) { Text(it) } }
        }
    }
}

/**
 * The search field and the store. A real `<form>`, so Enter searches; never per keystroke. The field
 * keeps what the reader typed until the next search reseeds it from the state.
 */
@Composable
private fun SearchForm(
    query: String,
    onSearch: (String) -> Unit,
    region: RegionUi?,
    onChooseStore: (MetadataLocale) -> Unit,
) {
    var text by remember(query) { mutableStateOf(query) }
    Form(attrs = {
        classes("bmx-search")
        attr("role", "search")
        onSubmit { event ->
            event.preventDefault()
            onSearch(text)
        }
    }) {
        Div(attrs = {
            classes("bmx-q")
            focusLanding(priority = 2)
        }) {
            Field(
                label = "Search for a match",
                value = text,
                onInput = { text = it },
                leading = WebIcon.Search,
                id = SEARCH_ID,
            )
        }
        Button(kind = ButtonKind.Secondary, submit = true) { Text("Search") }
    }
    region?.let { StoreMenu(it, onChooseStore) }
}

/**
 * The store, as ONE menu button — one Tab stop where ten pills used to be. Choosing a store searches
 * it for this search only; the library's store is the admin's.
 */
@Composable
internal fun StoreMenu(
    region: RegionUi,
    onChooseStore: (MetadataLocale) -> Unit,
) {
    val label = "${region.source.label} store: ${region.region.displayName}"
    Div(attrs = { classes("bmx-store") }) {
        PopupMenu(
            items =
                region.choices.map { choice ->
                    MenuAction(
                        choice.displayName,
                        WebIcon.Compass,
                        { onChooseStore(choice) },
                        checked =
                            choice == region.region,
                    )
                },
            label = "$label. Just this search",
            icon = WebIcon.Compass,
            triggerClasses = arrayOf("bmx-store-b"),
            triggerText = label,
        )
    }
}

/** "Hardcover didn't answer, so these results are from Audible and iTunes." with Retry Hardcover. */
@Composable
private fun PartialBanner(
    partial: PartialFailure,
    onRetry: () -> Unit,
) {
    val failed = sourcesText(partial.failed)
    Div(attrs = { classes("bmx-banner") }) {
        P { Text("$failed didn't answer, so these results are from ${sourcesText(partial.answered)}.") }
        Button(kind = ButtonKind.Secondary, size = ButtonSize.Sm, onClick = onRetry) { Text("Retry $failed") }
    }
}

@Composable
private fun CandidateGroup(
    title: String,
    candidates: List<CandidateUi>,
    results: FindUiState.Results,
    busy: Boolean,
    onPick: (CandidateUi) -> Unit,
) {
    if (candidates.isEmpty()) return
    Div(attrs = { classes("bmx-group") }) {
        H3(attrs = { classes("bmx-group-t") }) {
            Text(title)
            Span(attrs = { classes("bmx-count") }) { Text(candidates.size.toString()) }
        }
        Ul(attrs = {
            classes("bmx-rows")
            if (busy) attr("aria-busy", "true")
        }) {
            candidates.forEach { candidate ->
                Li { CandidateRow(candidate, picked = candidate.key == results.pickedKey, onPick = onPick) }
            }
        }
    }
}

/** One match: badges, title, the metadata line, up to three reasons, where it was found. */
@Composable
private fun CandidateRow(
    candidate: CandidateUi,
    picked: Boolean,
    onPick: (CandidateUi) -> Unit,
) {
    val strong = candidate.tier == MatchTier.STRONG
    DomButton(attrs = {
        classes("bmx-row")
        attr("type", "button")
        attr("id", rowId(candidate))
        if (picked) attr("aria-current", "true")
        onClick { onPick(candidate) }
    }) {
        Art(url = candidate.coverUrl)
        Span(attrs = { classes("bmx-row-m") }) {
            if (candidate.isBest || candidate.isCurrentLink) {
                Span(attrs = { classes("bmx-badges") }) {
                    if (candidate.isBest) Span(attrs = { classes("bmx-badge", "is-best") }) { Text("Best match") }
                    if (candidate.isCurrentLink) Span(attrs = { classes("bmx-badge") }) { Text("Your current link") }
                }
            }
            Span(attrs = { classes("bmx-row-t") }) { Text(candidate.title) }
            candidateMetaText(candidate).takeIf { it.isNotBlank() }?.let {
                Span(attrs = { classes("bmx-row-meta") }) { Text(it) }
            }
            if (candidate.reasons.isNotEmpty()) {
                Span(attrs = { classes("bmx-reasons") }) {
                    candidate.reasons.forEach { reason ->
                        Span(attrs = {
                            classes("bmx-reason")
                            if (strong) classes("is-strong")
                        }) {
                            if (strong) Icon(WebIcon.Check, size = SMALL_ICON)
                            Text(reasonText(reason))
                        }
                    }
                }
            }
            if (candidate.foundIn.isNotEmpty()) {
                Span(attrs = { classes("bmx-row-found") }) { Text(foundInText(candidate.foundIn)) }
            }
        }
    }
}

/** Why there is nothing to show, and the way forward (W-05). */
@Composable
private fun FailureCard(
    failure: FindFailure,
    session: BookMatchSession,
) {
    EmptyState(
        title = failureTitle(failure),
        body = failureBody(failure),
        look = EmptyLook.Inset,
        marker = "bmx-failure",
        action = {
            Div(attrs = { classes("bmx-fail-acts") }) { FailureActions(failure, session) }
            if (failure is FindFailure.RateLimited) P(attrs = { classes("bmx-note") }) { Text(NOTHING_WAS_CHANGED) }
        },
    )
}

@Composable
private fun FailureActions(
    failure: FindFailure,
    session: BookMatchSession,
) {
    when (failure) {
        is FindFailure.NotFoundInStore -> {
            failure.suggestions.take(MAX_STORE_SUGGESTIONS).forEach { store ->
                Button(kind = ButtonKind.Secondary, onClick = { session.chooseStoreForThisSearch(store) }) {
                    Text("Try ${store.displayName}")
                }
            }
            Button(kind = ButtonKind.Ghost, onClick = session.searchByTitle) { Text("Search by title") }
        }

        FindFailure.NothingFound -> {
            Button(kind = ButtonKind.Secondary, onClick = session.searchByTitle) { Text("Search by title") }
        }

        is FindFailure.RateLimited -> {
            val waiting = failure.secondsRemaining > 0
            // aria-disabled, not disabled: the countdown ending must not have dropped focus on the way.
            Button(kind = ButtonKind.Secondary, onClick = session.retry, pressable = !waiting) {
                Text(if (waiting) "Retry in ${countdownText(failure.secondsRemaining)}" else "Retry")
            }
        }

        else -> {
            Button(kind = ButtonKind.Secondary, onClick = session.retry) { Text("Retry") }
        }
    }
}

/** A catalogue cover, or a quiet tile where there is none. Decorative: the title is beside it. */
@Composable
internal fun Art(
    url: String?,
    big: Boolean = false,
) {
    if (url == null) {
        Span(attrs = {
            classes("bmx-art", "bmx-art-blank")
            if (big) classes("is-big")
            attr("aria-hidden", "true")
        }) { Icon(WebIcon.Book, size = SMALL_ICON) }
    } else {
        Img(src = url, alt = "", attrs = {
            classes("bmx-art")
            if (big) classes("is-big")
            attr("loading", "lazy")
        })
    }
}

/** What the search field holds: the query each state carries. */
internal fun queryOf(find: FindUiState): String =
    when (find) {
        is FindUiState.Searching -> find.query
        is FindUiState.Results -> find.query
        is FindUiState.Failed -> find.query
    }

private fun resultCountText(count: Int): String = if (count == 1) "1 result" else "$count results"

internal const val SEARCH_ID = "bmx-search"
private const val ART_WIDTH = 112
internal const val SMALL_ICON = 16
private const val MAX_STORE_SUGGESTIONS = 2
