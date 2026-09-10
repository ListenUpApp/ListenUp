package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsItemRef
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.imports.BookSearchState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

/**
 * One import run, from a picked file to a written history.
 *
 * Pure in [state]; the store wiring lives one level up.
 *
 * ⛔ **There is no way back.** The ViewModel's own KDoc calls this "a destructive pipeline, not a
 * wizard with a back button", and the states have no step-back transition to offer. Every phase
 * before Review therefore shows no way out except abandoning the page, and Review is the last point
 * at which anything can be changed — which is exactly why every decision is asked for there.
 *
 * **An ABS user with no answer is skipped, and the page says so rather than assuming.** The
 * ViewModel treats an unresolved user as skipped; leaving that implicit would mean an admin who
 * missed a row silently imports nothing for that person and finds out afterwards.
 */
@Suppress("LongParameterList")
@Composable
fun ImportFlowPage(
    state: ImportFlowUiState,
    onStart: (File) -> Unit,
    onMapUser: (AbsUserMatch, UserId) -> Unit,
    onSkipUser: (AbsUserMatch) -> Unit,
    onOpenBookSearch: (AbsItemId) -> Unit,
    onCloseBookSearch: () -> Unit,
    onBookSearchQuery: (String) -> Unit,
    onSelectBook: (AbsItemId, BookId) -> Unit,
    onSkipBook: (AbsItemId) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onOpenImports: () -> Unit,
) {
    Div(attrs = { classes("iflow") }) {
        // Only where leaving is harmless: before a file is picked, and after the run has ended.
        if (state is ImportFlowUiState.Idle || state is ImportFlowUiState.Done || state is ImportFlowUiState.Error) {
            Button(attrs = {
                classes(BTN_SECONDARY, "iflow-back")
                attr("type", VALUE_BUTTON)
                onClick { onOpenImports() }
            }) { Text("← Imports") }
        }

        H1(attrs = { classes("iflow-title") }) { Text("Import from Audiobookshelf") }

        when (state) {
            ImportFlowUiState.Idle -> {
                IdleStep(onStart)
            }

            is ImportFlowUiState.Uploading -> {
                Phase("Uploading ${state.filename}…", "Leave this page open until the upload finishes.")
            }

            is ImportFlowUiState.Analyzing -> {
                Phase(
                    headline = analyzingHeadline(state),
                    detail = "Matching listeners and books against your library. Nothing is written yet.",
                )
            }

            is ImportFlowUiState.Review -> {
                ReviewStep(
                    state = state,
                    onMapUser = onMapUser,
                    onSkipUser = onSkipUser,
                    onOpenBookSearch = onOpenBookSearch,
                    onCloseBookSearch = onCloseBookSearch,
                    onBookSearchQuery = onBookSearchQuery,
                    onSelectBook = onSelectBook,
                    onSkipBook = onSkipBook,
                    onApply = onApply,
                )
            }

            is ImportFlowUiState.Applying -> {
                Phase(
                    headline = applyingHeadline(state),
                    detail = "Writing history. This cannot be undone from here.",
                )
            }

            is ImportFlowUiState.Done -> {
                DoneStep(state, onOpenImports)
            }

            is ImportFlowUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("The import stopped") }
                    P(attrs = { attr("role", "alert") }) { Text(state.error.message) }
                    Button(attrs = {
                        classes(BTN_PRIMARY)
                        attr("type", VALUE_BUTTON)
                        onClick { onReset() }
                    }) { Text("Start again") }
                }
            }
        }
    }
}

/** Pick the backup. Nothing else exists on the page until one is chosen. */
@Composable
private fun IdleStep(onStart: (File) -> Unit) {
    var input by remember { mutableStateOf<HTMLInputElement?>(null) }

    Div(attrs = { classes("iflow-idle") }) {
        P(attrs = { classes("iflow-body") }) {
            Text(
                "Choose an Audiobookshelf backup. It is read on the server and matched against your " +
                    "library — you decide what is written before anything is.",
            )
        }
        Button(attrs = {
            classes(BTN_PRIMARY)
            attr("type", VALUE_BUTTON)
            onClick { input?.click() }
        }) { Text("Choose a backup") }
        Input(type = InputType.File, attrs = {
            id("iflow-file-input")
            attr("accept", ".zip,.audiobookshelf,application/zip")
            style { property("display", "none") }
            ref { element ->
                input = element
                onDispose { input = null }
            }
            onChange { event ->
                val element = event.target as HTMLInputElement
                element.files?.item(0)?.let(onStart)
                // Re-picking the same file must fire change again next time.
                element.value = ""
            }
        })
    }
}

/**
 * A phase with nothing to decide: a headline and a sentence.
 *
 * Announced politely, because the headline changes under the reader without them acting, and a
 * long import is exactly when someone looks away.
 */
@Composable
private fun Phase(
    headline: String,
    detail: String,
) {
    Div(attrs = {
        classes("iflow-live")
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        Div(attrs = { classes("skel", "iflow-bar") })
        P(attrs = { classes("iflow-phase") }) { Text(headline) }
        P(attrs = { classes("iflow-body") }) { Text(detail) }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ReviewStep(
    state: ImportFlowUiState.Review,
    onMapUser: (AbsUserMatch, UserId) -> Unit,
    onSkipUser: (AbsUserMatch) -> Unit,
    onOpenBookSearch: (AbsItemId) -> Unit,
    onCloseBookSearch: () -> Unit,
    onBookSearchQuery: (String) -> Unit,
    onSelectBook: (AbsItemId, BookId) -> Unit,
    onSkipBook: (AbsItemId) -> Unit,
    onApply: () -> Unit,
) {
    val undecided =
        state.analysis.userMatches.count {
            it.absUserId !in state.userMappings && it.absUserId !in state.skippedUsers
        }

    FormSection(title = "Listeners") {
        P(attrs = { classes(HINT) }) {
            Text(
                "Say who each Audiobookshelf listener is here. Anyone left undecided is " +
                    "skipped — no history is imported for them.",
            )
        }
        state.analysis.userMatches.forEach { match ->
            UserRow(
                match = match,
                mappedTo = state.userMappings[match.absUserId],
                isSkipped = match.absUserId in state.skippedUsers,
                candidates = state.listenupUsers,
                onMap = { userId -> onMapUser(match, userId) },
                onSkip = { onSkipUser(match) },
            )
        }
        if (state.listenupUsers.isEmpty()) {
            // The ViewModel treats a failed user-list load as non-fatal, so the page has to
            // explain the empty picker rather than looking broken.
            P(attrs = { classes(HINT) }) {
                Text(
                    "The list of listeners could not be loaded, so there is nobody to pick. " +
                        "You can still skip each one.",
                )
            }
        }
    }

    val needsAttention = state.analysis.ambiguous + state.analysis.unmatched
    if (needsAttention.isNotEmpty()) {
        FormSection(title = "Books needing a decision") {
            P(attrs = { classes(HINT) }) {
                Text("These could not be matched confidently. Everything else was matched already and is not listed.")
            }
            needsAttention.forEach { item ->
                BookRow(
                    item = item,
                    decision = state.bookOverrides[item.absItemId],
                    isDecided = item.absItemId in state.bookOverrides,
                    onFind = { onOpenBookSearch(item.absItemId) },
                    onSkip = { onSkipBook(item.absItemId) },
                )
            }
        }
    }

    Div(attrs = { classes("iflow-apply") }) {
        Span(attrs = { classes("iflow-tally") }) { Text(applyTally(state, undecided)) }
        Button(attrs = {
            classes(BTN_PRIMARY)
            attr("type", VALUE_BUTTON)
            onClick { onApply() }
        }) { Text("Import") }
    }

    state.bookSearch?.let { search ->
        BookSearchDialog(
            search = search,
            onQuery = onBookSearchQuery,
            onSelect = { bookId -> onSelectBook(search.absItemId, bookId) },
            onDismiss = onCloseBookSearch,
        )
    }
}

@Composable
private fun UserRow(
    match: AbsUserMatch,
    mappedTo: UserId?,
    isSkipped: Boolean,
    candidates: List<AdminUserInfo>,
    onMap: (UserId) -> Unit,
    onSkip: () -> Unit,
) {
    Div(attrs = { classes("iflow-row") }) {
        Div(attrs = { classes("iflow-row-t") }) {
            Span(attrs = { classes("iflow-row-n") }) { Text(match.absUsername) }
            match.absEmail?.takeIf { it.isNotBlank() }?.let { email ->
                Span(attrs = { classes("iflow-row-s") }) { Text(email) }
            }
        }
        SelectField(
            label = "Is",
            value = mappedTo?.value,
            options = candidates.map { SelectOption(it.id, it.displayName ?: it.email) },
            onSelect = { picked -> picked?.let { onMap(UserId(it)) } },
            emptyLabel = if (isSkipped) "Skipped" else "Nobody yet",
            id = "iflow-user-${match.absUserId.value}",
        )
        Button(attrs = {
            classes(BTN_SECONDARY, "iflow-skip")
            attr("type", VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Skip ${match.absUsername}")
            disabledWhen(isSkipped)
            onClick { onSkip() }
        }) { Text(if (isSkipped) "Skipped" else "Skip") }
    }
}

@Composable
private fun BookRow(
    item: AbsItemRef,
    decision: BookId?,
    isDecided: Boolean,
    onFind: () -> Unit,
    onSkip: () -> Unit,
) {
    Div(attrs = { classes("iflow-row") }) {
        Div(attrs = { classes("iflow-row-t") }) {
            Span(attrs = { classes("iflow-row-n") }) { Text(item.title) }
            // The path is what tells two same-titled items apart, and it is what the admin
            // recognises from their own disk.
            item.relPath?.takeIf { it.isNotBlank() }?.let { path ->
                Span(attrs = { classes("iflow-row-s") }) { Text(path) }
            }
        }
        Span(attrs = { classes("iflow-decision") }) { Text(bookDecisionLabel(isDecided, decision)) }
        Button(attrs = {
            classes(BTN_SECONDARY, "iflow-find")
            attr("type", VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Find the book for ${item.title}")
            onClick { onFind() }
        }) { Text("Find") }
        Button(attrs = {
            classes(BTN_SECONDARY, "iflow-skip")
            attr("type", VALUE_BUTTON)
            attr(ATTR_ARIA_LABEL, "Skip ${item.title}")
            onClick { onSkip() }
        }) { Text("Skip") }
    }
}

/** Search the library for the book an ABS item should map to. */
@Composable
private fun BookSearchDialog(
    search: BookSearchState,
    onQuery: (String) -> Unit,
    onSelect: (BookId) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalDialog(open = true, title = "Find the book", onDismiss = onDismiss) {
        Field(
            label = "Search the library",
            value = search.query,
            onInput = onQuery,
            id = "iflow-book-query",
        )
        Div(attrs = { classes("iflow-results") }) {
            when {
                search.isSearching -> {
                    Nothing("Searching…")
                }

                // Two different nothings, as everywhere else: a reader who has typed a title needs
                // to know the search ran and came back empty.
                search.query.isBlank() -> {
                    Nothing("Type a title or an author.")
                }

                search.results.isEmpty() -> {
                    Nothing("Nothing matched \"${search.query}\".")
                }

                else -> {
                    search.results.forEach { hit ->
                        Button(attrs = {
                            classes("iflow-result")
                            attr("type", VALUE_BUTTON)
                            onClick { onSelect(hit.bookId) }
                        }) {
                            Span(attrs = { classes("iflow-result-t") }) { Text(hit.title) }
                            Span(attrs = { classes("iflow-result-b") }) { Text(hit.author) }
                            Icon(WebIcon.Plus, size = SMALL_ICON)
                        }
                    }
                }
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(attrs = {
                classes("btn")
                attr("type", VALUE_BUTTON)
                onClick { onDismiss() }
            }) { Text("Cancel") }
        }
    }
}

@Composable
private fun DoneStep(
    state: ImportFlowUiState.Done,
    onOpenImports: () -> Unit,
) {
    Div(attrs = { classes("empty") }) {
        H3 { Text("Imported") }
        P { Text(doneSummary(state)) }
        // The books it could not place are the reason a number looks lower than expected, so they
        // are stated rather than left as a silent difference.
        if (state.result.booksNotInLibrary > 0) {
            P(attrs = { classes("iflow-note") }) {
                Text(
                    "${countLabel(state.result.booksNotInLibrary, "book")} in that history " +
                        "${if (state.result.booksNotInLibrary == 1) "is" else "are"} not in this library, " +
                        "so their progress could not be imported.",
                )
            }
        }
        Button(attrs = {
            classes(BTN_PRIMARY)
            attr("type", VALUE_BUTTON)
            onClick { onOpenImports() }
        }) { Text("Back to imports") }
    }
}

@Composable
private fun Nothing(text: String) {
    P(attrs = { classes("iflow-none") }) { Text(text) }
}

private fun analyzingHeadline(state: ImportFlowUiState.Analyzing): String =
    state.currentItem?.let { "Reading ${state.done} of ${state.total}: $it" }
        ?: "Reading ${state.done} of ${state.total}…"

private fun applyingHeadline(state: ImportFlowUiState.Applying): String =
    "Writing ${state.done} of ${state.total} — ${countLabel(state.sessionsWritten, "session")} so far"

/** What each undecided book row currently says. */
private fun bookDecisionLabel(
    isDecided: Boolean,
    decision: BookId?,
): String =
    when {
        !isDecided -> "Undecided"
        decision == null -> "Skipped"
        else -> "Matched"
    }

/**
 * What pressing Import will actually do, in the numbers that matter.
 *
 * The undecided count is named rather than folded into "skipped", because an admin who has not
 * finished is the one person this sentence is for.
 */
private fun applyTally(
    state: ImportFlowUiState.Review,
    undecided: Int,
): String {
    val mapped = countLabel(state.userMappings.size, "listener")
    val sessions = countLabel(state.analysis.importableSessionCount, "session")
    return if (undecided > 0) {
        "$mapped mapped, $sessions to import · $undecided still undecided and will be skipped"
    } else {
        "$mapped mapped, $sessions to import"
    }
}

private fun doneSummary(state: ImportFlowUiState.Done): String =
    "${countLabel(state.result.importedCount, "record")} written, " +
        "${countLabel(state.result.sessionsImported, "session")} of listening history."

private const val BTN_PRIMARY = "btn-c"

private const val BTN_SECONDARY = "btn-o"

private const val HINT = "iflow-hint"

private const val ATTR_ARIA_LABEL = "aria-label"

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16
