package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSearchState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * `/book/{id}/hardcover` — Find on Hardcover for one book (spec B5), built to the approved sync
 * canvas. Pure in [state]; the session, the toast and the way back to the book live one level up.
 * Every string is the English text of en.json's `hardcover` group.
 *
 * The results come in two groups: "By {author}", the book's own author, drawn strong, and "Other
 * results" drawn quiet — the shape that tells the real book from a third-party summary at a glance.
 * With nothing by the author, a card says so and offers the search worth trying, over one "Results"
 * group. Picking a result links it in one press; there is no confirm, because the toast offers Undo.
 *
 * Each result is a row with one "Pick" button named for what it picks, not a clickable row: a button
 * is what a keyboard and a screen reader already know how to press.
 */
@Composable
fun HardcoverMatchPage(
    state: HardcoverMatchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onPick: (hcBookId: Long) -> Unit,
    onRemoveMatch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBook: () -> Unit,
) {
    val ready = state as? HardcoverMatchUiState.Ready
    Div(attrs = { classes("hc", "hc-find") }) {
        Breadcrumb(
            trail = listOfNotNull("Library", ready?.bookTitle, TITLE),
            onNavigate = { index -> if (index == 0) onOpenLibrary() else onOpenBook() },
        )
        PageHeader(title = TITLE, subtitle = ready?.let { "Matching ${it.bookTitle}" })
        when (state) {
            HardcoverMatchUiState.Loading -> {
                LoadingState()
            }

            HardcoverMatchUiState.BookMissing -> {
                EmptyState(title = "This book is no longer in your library.", look = EmptyLook.Inline)
            }

            is HardcoverMatchUiState.Ready -> {
                ReadyMatch(state, onQueryChange, onSearch, onSearchFor, onPick, onRemoveMatch)
            }
        }
    }
}

@Composable
private fun ReadyMatch(
    state: HardcoverMatchUiState.Ready,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onPick: (Long) -> Unit,
    onRemoveMatch: () -> Unit,
) {
    val busy = state.linkingId != null || state.isRemoving
    state.currentMatch?.let {
        CurrentMatch(
            it,
            isRemoving = state.isRemoving,
            enabled = !busy,
            onRemove = onRemoveMatch,
        )
    }

    // A real <form>, so Enter searches — as the Audible search does. Never per keystroke: a search
    // spends the reader's Hardcover budget.
    Form(attrs = {
        classes("hc-search")
        attr("role", "search")
        onSubmit { event ->
            event.preventDefault()
            onSearch()
        }
    }) {
        Field(
            label = "Search Hardcover",
            value = state.query,
            onInput = onQueryChange,
            leading = WebIcon.Search,
            placeholder = "Title or author",
            id = QUERY_ID,
        )
        Button(kind = ButtonKind.Primary, size = ButtonSize.Lg, submit = true, enabled = state.query.isNotBlank()) {
            Text("Search")
        }
    }

    when (val search = state.search) {
        HardcoverSearchState.Searching -> {
            LoadingState(label = "Searching Hardcover…")
        }

        HardcoverSearchState.NoResults -> {
            EmptyState(
                title = "No matches on Hardcover",
                body = "Nothing found for “${state.query.trim()}”. Fewer words usually help — try one of these.",
                icon = WebIcon.Search,
                look = EmptyLook.Inset,
                action = { Suggestions(state.suggestions, onSearchFor) },
            )
        }

        is HardcoverSearchState.Failed -> {
            Div(attrs = { classes("hc-failed") }) {
                P(attrs = {
                    classes("hc-failed-t")
                    attr("role", "alert")
                }) { Text("Hardcover couldn't be searched just now.") }
                Button(kind = ButtonKind.Secondary, onClick = onSearch) {
                    Icon(WebIcon.Refresh, size = BUTTON_ICON)
                    Text("Try again")
                }
            }
        }

        is HardcoverSearchState.Results -> {
            Results(state, search, enabled = !busy, onSearchFor = onSearchFor, onPick = onPick)
        }
    }
}

/**
 * The results: "By {author}" strong over "Other results" quiet — or, with nothing by the author, the
 * card that says so (and offers the search worth trying) over one "Results" group.
 */
@Composable
private fun Results(
    state: HardcoverMatchUiState.Ready,
    search: HardcoverSearchState.Results,
    enabled: Boolean,
    onSearchFor: (String) -> Unit,
    onPick: (Long) -> Unit,
) {
    val byAuthor = search.byAuthor
    if (byAuthor.isNotEmpty()) {
        ResultGroup("By ${state.bookAuthors}", byAuthor, strong = true, state.linkingId, enabled, onPick)
        if (search.others.isNotEmpty()) {
            ResultGroup("Other results", search.others, strong = false, state.linkingId, enabled, onPick)
        }
        return
    }
    if (state.bookAuthors.isNotBlank()) {
        Div(attrs = {
            classes("hc-weak")
            attr("role", "status")
        }) {
            Span(attrs = {
                classes("hc-weak-i")
                attr(ARIA_HIDDEN, "true")
            }) { Icon(WebIcon.Search, size = BUTTON_ICON) }
            Span(attrs = { classes("hc-weak-text") }) {
                Span(attrs = { classes("hc-weak-t") }) { Text("Lots of results — none by ${state.bookAuthors}") }
                Span(attrs = { classes("hc-match-by") }) { Text("Use the full title, or add the author.") }
            }
            Suggestions(state.suggestions, onSearchFor)
        }
    }
    ResultGroup("Results", search.rows, strong = false, state.linkingId, enabled, onPick)
}

/** The searches worth one press, each as `Search “…”`. Nothing when there are none. */
@Composable
private fun Suggestions(
    suggestions: List<String>,
    onSearchFor: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return
    Div(attrs = { classes("hc-suggest") }) {
        suggestions.forEach { suggestion ->
            Button(kind = ButtonKind.Secondary, onClick = { onSearchFor(suggestion) }) {
                Icon(WebIcon.Search, size = BUTTON_ICON)
                Text("Search “$suggestion”")
            }
        }
    }
}

@Composable
private fun ResultGroup(
    title: String,
    rows: List<HardcoverCandidateRow>,
    strong: Boolean,
    linkingId: Long?,
    enabled: Boolean,
    onPick: (Long) -> Unit,
) {
    Panel(title = title, flush = true) {
        Ul(attrs = {
            classes("hc-results")
            if (!strong) classes("is-quiet")
        }) {
            rows.forEach { row ->
                CandidateRow(row, isLinking = linkingId == row.hcBookId, enabled = enabled, onPick = onPick)
            }
        }
    }
}

@Composable
private fun CandidateRow(
    row: HardcoverCandidateRow,
    isLinking: Boolean,
    enabled: Boolean,
    onPick: (Long) -> Unit,
) {
    Li(attrs = { classes("hc-result") }) {
        Span(attrs = { classes("hc-result-main") }) {
            Span(attrs = { classes("hc-result-title") }) { Text(row.title) }
            Span(attrs = { classes("hc-match-by") }) { Text(names(row.authors) ?: "Unknown author") }
        }
        Span(attrs = { classes("hc-result-format") }) {
            if (row.hasAudiobookEdition) Span(attrs = { classes("hc-tell") }) { Text(AUDIOBOOK) }
        }
        Span(attrs = { classes("hc-result-year", "mono") }) { Text(row.releaseYear?.toString().orEmpty()) }
        Span(attrs = { classes("hc-result-ratings", "mono") }) { Text(ratingsLine(row.ratingsCount)) }
        Span(attrs = { classes("hc-result-act") }) {
            Button(
                kind = ButtonKind.Secondary,
                onClick = { onPick(row.hcBookId) },
                enabled = enabled,
                label = "Match to ${row.title.withDetail(row.detail())}",
                attrs = { if (isLinking) attr("aria-busy", "true") },
            ) { Text("Pick") }
        }
    }
}

/** The book matched today, above the search, with Remove match and what removing it costs. */
@Composable
private fun CurrentMatch(
    current: HardcoverMatchedBook,
    isRemoving: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Panel(title = "Matched now") {
        Div(attrs = { classes("hc-book") }) {
            Span(attrs = { classes("hc-match-title") }) { Text(current.title ?: "Matched on Hardcover") }
            matchedLine(current)?.let { Span(attrs = { classes("hc-match-by") }) { Text(it) } }
            P(attrs = { classes("hc-lede") }) { Text("ListenUp stops syncing this book until you pick one.") }
            Button(
                kind = ButtonKind.Secondary,
                onClick = onRemove,
                enabled = enabled,
                attrs = { if (isRemoving) attr("aria-busy", "true") },
            ) { Text("Remove match") }
        }
    }
}

/** What the pick's toast says: "Matched to Project Hail Mary (Audiobook, 2021)", the detail when there is any. */
internal fun linkedToastText(picked: HardcoverCandidateRow): String =
    "Matched to ${picked.title.withDetail(picked.detail())}"

/** "Audiobook, 2021" — what tells one pick from another of the same title; empty when Hardcover says neither. */
private fun HardcoverCandidateRow.detail(): String =
    listOfNotNull(AUDIOBOOK.takeIf { hasAudiobookEdition }, releaseYear?.toString()).joinToString(", ")

private fun String.withDetail(detail: String): String = if (detail.isEmpty()) this else "$this ($detail)"

private fun ratingsLine(count: Int?): String =
    when {
        count == null || count <= 0 -> "No ratings yet"
        count == 1 -> "1 rating"
        else -> "${RatingLabels.compactCount(count)} ratings"
    }

/** The first two credited names — Hardcover lists narrators among them too — or null when there are none. */
internal fun names(authors: List<String>): String? = authors.take(SHOWN_NAMES).joinToString(", ").ifBlank { null }

/**
 * A matched book's second line: its names, "Audiobook" when the match names an edition (an edition is
 * only ever linked for an audiobook), and its year — "Andy Weir · Audiobook · 2021".
 */
internal fun matchedLine(match: HardcoverMatchedBook): String? =
    listOfNotNull(
        names(match.authors),
        AUDIOBOOK.takeIf { match.hcEditionId != null },
        match.releaseYear?.toString(),
    ).joinToString(" · ").ifBlank { null }

/** en.json's `hardcover.match_title` — the page's title and the breadcrumb's last step. */
private const val TITLE = "Find on Hardcover"

/** en.json's `hardcover.match_audiobook`. */
private const val AUDIOBOOK = "Audiobook"
private const val QUERY_ID = "hc-query"
private const val SHOWN_NAMES = 2
private const val BUTTON_ICON = 16
private const val ARIA_HIDDEN = "aria-hidden"
