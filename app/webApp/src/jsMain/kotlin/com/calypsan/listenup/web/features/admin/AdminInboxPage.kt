package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.InboxBookItem
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.web.design.BulkAction
import com.calypsan.listenup.web.design.BulkBar
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds

/**
 * The inbox — what the scanner brought in and what it could not.
 *
 * Two halves that are independent by design, and the ViewModel is explicit that they are: books
 * held for review are things awaiting a decision, scan issues are things that went wrong and
 * produced no book at all. Either can be empty while the other has content, so neither is a
 * section of the other and the page says so only when both are empty.
 *
 * Pure in [state]; the store wiring lives one level up.
 *
 * **Releasing is public.** A released book joins the shared collection every member can see, and
 * that is not undone by pressing something else — so it asks first, exactly as both native clients
 * do. The scan-issue Dismiss does not ask: it hides a report, touches nothing on disk, and the
 * next scan that hits the same folder raises it again.
 */
@Composable
fun AdminInboxPage(
    state: AdminInboxUiState,
    onToggleBook: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRelease: () -> Unit,
    onDismissIssue: (String) -> Unit,
    onClearError: () -> Unit,
    onClearReleaseResult: () -> Unit,
    onRetry: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("inbox") }) {
        Button(attrs = {
            classes("btn-o", "inbox-back")
            attr("type", VALUE_BUTTON)
            onClick { onOpenAdmin() }
        }) { Text("← Admin") }

        H1(attrs = { classes("inbox-title") }) { Text("Inbox") }
        P(attrs = { classes("inbox-sub") }) { Text("Books that need a look before they join your library.") }

        when (state) {
            AdminInboxUiState.Loading -> {
                Div(attrs = { classes("skel", "inbox-skel") })
            }

            is AdminInboxUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("The inbox can't be shown") }
                    P { Text(state.message) }
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", VALUE_BUTTON)
                        onClick { onRetry() }
                    }) { Text("Try again") }
                }
            }

            is AdminInboxUiState.Ready -> {
                ReadyContent(
                    state = state,
                    onToggleBook = onToggleBook,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onRelease = onRelease,
                    onDismissIssue = onDismissIssue,
                    onClearError = onClearError,
                    onClearReleaseResult = onClearReleaseResult,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: AdminInboxUiState.Ready,
    onToggleBook: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRelease: () -> Unit,
    onDismissIssue: (String) -> Unit,
    onClearError: () -> Unit,
    onClearReleaseResult: () -> Unit,
) {
    // Whether the release confirmation is up. View-local by nature: nothing has been asked of the
    // server yet, so there is nothing for the ViewModel to hold.
    var confirmingRelease by remember { mutableStateOf(false) }

    state.error?.let { message ->
        Notice(classes = "inbox-err", text = message, onDismiss = onClearError)
    }
    state.lastReleasedCount?.let { count ->
        Notice(
            classes = "inbox-note",
            text = if (count == 1) "Released 1 book" else "Released $count books",
            onDismiss = onClearReleaseResult,
        )
    }

    if (state.hasBooks) {
        WaitingForReview(state, onToggleBook, onSelectAll, onClearSelection)
    }
    if (state.hasIssues) {
        NeedsAttention(state.scanIssues, onDismissIssue)
    }
    // Both halves absent is the good outcome, not a failure — say so rather than trailing off
    // after the heading and looking like the page failed to finish loading.
    if (state.isEmpty) {
        Div(attrs = { classes("empty") }) {
            H3 { Text("Inbox empty") }
            P { Text("Newly scanned books will appear here, and so will anything the scan could not make sense of.") }
        }
    }

    if (state.hasSelection) {
        BulkBar(
            count = state.selectedCount,
            actions =
                listOf(
                    BulkAction(
                        label = if (state.isReleasing) "Releasing…" else "Release ${state.selectedCount}",
                        icon = WebIcon.Check,
                        onClick = { if (!state.isReleasing) confirmingRelease = true },
                    ),
                ),
            onClear = onClearSelection,
        )
    }

    ConfirmDialog(
        open = confirmingRelease,
        title = "Release without collections?",
        body =
            "These books will become visible to everyone on this server. You can put them in " +
                "collections afterwards from each book's own page.",
        confirmLabel = "Release anyway",
        onConfirm = {
            confirmingRelease = false
            onRelease()
        },
        onDismiss = { confirmingRelease = false },
    )
}

/** The books being held, and the selection that decides which of them go out. */
@Composable
private fun WaitingForReview(
    state: AdminInboxUiState.Ready,
    onToggleBook: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    Panel(
        title = "Waiting for review",
        trailing = {
            Button(attrs = {
                classes("btn-o", "inbox-selall")
                attr("type", VALUE_BUTTON)
                onClick { if (state.allSelected) onClearSelection() else onSelectAll() }
            }) { Text(if (state.allSelected) "Deselect all" else "Select all") }
        },
    ) {
        Div(attrs = { classes("inbox-books") }) {
            // `books` is the hydrated projection and may lag `bookIds` until Room catches up, so
            // the count comes from the ids — the authoritative set — while the rows come from
            // whatever has hydrated. A row that has not arrived yet is simply not drawn.
            state.books.forEach { book ->
                InboxBookRow(
                    book = book,
                    selected = book.id in state.selectedBookIds,
                    onToggle = { onToggleBook(book.id) },
                )
            }
        }
    }
}

@Composable
private fun InboxBookRow(
    book: InboxBookItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Button(attrs = {
        classes("inbox-book")
        if (selected) classes("is-sel")
        attr("type", VALUE_BUTTON)
        // The row IS the checkbox, so it has to say so — without this a screen reader announces a
        // button that gives no hint it has an on and an off.
        attr("role", "checkbox")
        attr("aria-checked", selected.toString())
        onClick { onToggle() }
    }) {
        Cover(
            title = book.title,
            imageUrl = coverUrl(book.id, book.coverHash, width = COVER_RUNG),
            size = COVER_SIZE,
            radius = COVER_RADIUS,
        )
        Div(attrs = { classes("inbox-book-t") }) {
            Span(attrs = { classes("inbox-book-title") }) { Text(book.title) }
            // Absent, not an empty line: a book whose author the scan could not work out is one of
            // the reasons it is sitting in here.
            book.author?.takeIf { it.isNotBlank() }?.let { author ->
                Span(attrs = { classes("inbox-book-by") }) { Text(author) }
            }
        }
        Span(attrs = { classes("inbox-book-dur") }) {
            Text(DurationFormatter.hoursMinutes(book.durationMs.milliseconds))
        }
        Div(attrs = { classes("inbox-tick") }) {
            if (selected) Icon(WebIcon.Check, size = TICK_ICON_SIZE)
        }
    }
}

/** The folders the scan walked but could not turn into a book. */
@Composable
private fun NeedsAttention(
    issues: List<ScanIssue>,
    onDismissIssue: (String) -> Unit,
) {
    Panel(title = "Needs attention") {
        P(attrs = { classes("inbox-hint") }) {
            Text("ListenUp walked these folders but couldn't make a book from them.")
        }
        Div(attrs = { classes("inbox-issues") }) {
            issues.forEach { issue -> IssueRow(issue, onDismissIssue) }
        }
    }
}

@Composable
private fun IssueRow(
    issue: ScanIssue,
    onDismissIssue: (String) -> Unit,
) {
    Div(attrs = { classes("inbox-issue") }) {
        Div(attrs = { classes("inbox-issue-t") }) {
            Span(attrs = { classes("inbox-issue-what") }) { Text(issue.reason.headline()) }
            // The path is what tells two issues apart, so it is the line under the headline
            // rather than a detail behind a disclosure.
            Span(attrs = { classes("inbox-issue-path") }) { Text(issue.rootRelPath) }
            Span(attrs = { classes("inbox-issue-fix") }) { Text(issue.reason.fixHint()) }
            // What the scan actually reported. Only some issues carry it, and it is the only part
            // of the row that is not written in advance.
            issue.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Span(attrs = { classes("inbox-issue-detail") }) { Text(detail) }
            }
        }
        Button(attrs = {
            classes("btn-o", "inbox-issue-x")
            attr("type", VALUE_BUTTON)
            onClick { onDismissIssue(issue.id) }
        }) { Text("Dismiss") }
    }
}

/** A dismissible line above the content — a transient failure, or the receipt for a release. */
@Composable
private fun Notice(
    classes: String,
    text: String,
    onDismiss: () -> Unit,
) {
    Div(attrs = { classes(classes) }) {
        Span(attrs = { classes("inbox-notice-t") }) { Text(text) }
        Button(attrs = {
            classes("inbox-notice-x")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Dismiss")
            onClick { onDismiss() }
        }) { Icon(WebIcon.X, size = TICK_ICON_SIZE) }
    }
}

/** What went wrong, in the reader's terms rather than the enum's. */
private fun ScanIssueReason.headline(): String =
    when (this) {
        ScanIssueReason.NO_RECOGNIZED_AUDIO -> "No audio in this folder"
        ScanIssueReason.FILE_UNREADABLE -> "A file here couldn't be read"
        ScanIssueReason.METADATA_PARSE_FAILED -> "The tags couldn't be read"
        ScanIssueReason.TITLE_INFERENCE_FAILED -> "No title could be worked out"
        ScanIssueReason.UNKNOWN -> "Something went wrong here"
    }

/**
 * What to do about it.
 *
 * Every reason has one, because an issue row that only names the problem leaves the reader with a
 * list of things that are wrong and no idea which of them they can fix.
 */
private fun ScanIssueReason.fixHint(): String =
    when (this) {
        ScanIssueReason.NO_RECOGNIZED_AUDIO -> "Add the audio files, or dismiss this if the folder isn't a book."
        ScanIssueReason.FILE_UNREADABLE -> "Check the file is still there and ListenUp has permission to read it."
        ScanIssueReason.METADATA_PARSE_FAILED -> "The audio is there, but its metadata is unreadable. Re-tagging usually fixes it."
        ScanIssueReason.TITLE_INFERENCE_FAILED -> "Name the folder after the book, or give the audio a title tag."
        ScanIssueReason.UNKNOWN -> "The scan couldn't say why. The details below are what it reported."
    }

/** Every button here is an action, never a form submit. */
private const val VALUE_BUTTON = "button"

private const val COVER_SIZE = 56

/** 2× the rendered width, so the derivative the server picks stays sharp on dense displays. */
private const val COVER_RUNG = 112

private const val COVER_RADIUS = 8

private const val TICK_ICON_SIZE = 16
