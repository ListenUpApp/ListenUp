package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.focusAsLanding
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement

/**
 * What a receipt is about, which decides what it says: a book's counts with See what changed, or a
 * person's "Changed photo and biography for Ray Porter" with Undo alone (D6) — the sentence already
 * names both changes.
 */
sealed interface ReceiptSubject {
    /** A book's receipt, on Book Detail (W-04). */
    data object Book : ReceiptSubject

    /** A person's receipt, on the contributor page (W-07), named for them. */
    data class Person(
        val name: String,
    ) : ReceiptSubject
}

/**
 * The receipt Book Detail or the contributor page shows after Apply (W-04, W-07): "Changed 5 fields,
 * cover from Hardcover, 16 chapter names", with See what changed and Undo — or, for a [subject] that is
 * a person, "Changed photo and biography for Ray Porter" with Undo.
 *
 * A `role=status` region that is always in the page, so what lands in it is announced; and focus
 * moves to it when it arrives and again when Undo settles, so a keyboard is where the news is. The
 * web cannot tell whether a screen reader is running, so the receipt is never timed: it stays until
 * it is dismissed or the reader leaves the book — never a seven-second toast (lesson M-T1).
 *
 * Dismissing it hands focus to the page's heading rather than letting it fall to `<body>`.
 */
@Composable
fun MatchReceiptRegion(
    state: MatchReceiptUiState,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    subject: ReceiptSubject = ReceiptSubject.Book,
) {
    var region by remember { mutableStateOf<HTMLElement?>(null) }
    var showingChanges by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(arrivalKey(state), region) {
        val element = region ?: return@LaunchedEffect
        if (state !is MatchReceiptUiState.None) focusAsLanding(element)
    }
    LaunchedEffect(dismissed, state) {
        if (!dismissed || state !is MatchReceiptUiState.None) return@LaunchedEffect
        dismissed = false
        val page = region?.ownerDocument?.querySelector(".shell-main h1, h1") as? HTMLElement
        page?.let(::focusAsLanding)
    }

    val dismiss = {
        dismissed = true
        onDismiss()
    }
    Div(attrs = {
        classes("bmx-receipt")
        attr("role", "status")
        attr("aria-live", "polite")
        attr("aria-label", "Match details")
        ref { element ->
            region = element
            onDispose { region = null }
        }
    }) {
        when (state) {
            MatchReceiptUiState.None -> {
                Unit
            }

            is MatchReceiptUiState.Shown -> {
                Shown(
                    state = state,
                    sentence = receiptSentence(state.receipt, subject),
                    onUndo = onUndo,
                    onSeeChanges = { showingChanges = true }.takeIf { subject == ReceiptSubject.Book },
                    onDismiss = dismiss,
                )
                ChangesDialog(open = showingChanges, receipt = state.receipt, onClose = { showingChanges = false })
            }

            MatchReceiptUiState.Undone -> {
                Settled("Match undone. Everything it changed is back.", dismiss)
            }

            MatchReceiptUiState.Expired -> {
                Settled(expiredSentence(subject), dismiss)
            }
        }
    }
}

@Composable
private fun Shown(
    state: MatchReceiptUiState.Shown,
    sentence: String,
    onUndo: () -> Unit,
    onSeeChanges: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    P(attrs = { classes("bmx-receipt-t") }) {
        Text(state.undoError?.let { nothingChanged(it.message) } ?: sentence)
    }
    Div(attrs = { classes("bmx-receipt-acts") }) {
        if (onSeeChanges != null && state.receipt.changes.isNotEmpty()) {
            Button(kind = ButtonKind.Ghost, onClick = onSeeChanges) { Text("See what changed") }
        }
        if (state.receipt.undoable) {
            // aria-disabled while it runs: the press must not drop its own focus to the top of the page.
            Button(kind = ButtonKind.Ghost, onClick = onUndo, pressable = !state.undoing) {
                Text(if (state.undoing) "Undoing…" else "Undo")
            }
        }
        Button(
            kind = ButtonKind.Icon,
            size = ButtonSize.Sm,
            onClick = onDismiss,
            label = "Dismiss",
            pressable = !state.undoing,
        ) {
            Icon(WebIcon.X, size = SMALL_ICON)
        }
    }
}

@Composable
private fun Settled(
    sentence: String,
    onDismiss: () -> Unit,
) {
    P(attrs = { classes("bmx-receipt-t") }) { Text(sentence) }
    Div(attrs = { classes("bmx-receipt-acts") }) {
        Button(kind = ButtonKind.Icon, size = ButtonSize.Sm, onClick = onDismiss, label = "Dismiss") {
            Icon(WebIcon.X, size = SMALL_ICON)
        }
    }
}

/** See what changed: every change the match made, with where it came from. */
@Composable
internal fun ChangesDialog(
    open: Boolean,
    receipt: MatchReceiptUi,
    onClose: () -> Unit,
) {
    ModalDialog(open = open, title = "What changed", onDismiss = onClose) {
        Ul(attrs = { classes("bmx-changes") }) {
            receipt.changes.flatMap(::changeLines).forEach { line -> Li { Text(line) } }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(kind = ButtonKind.Primary, onClick = onClose) { Text("Done") }
        }
    }
}

private fun receiptSentence(
    receipt: MatchReceiptUi,
    subject: ReceiptSubject,
): String =
    when (subject) {
        ReceiptSubject.Book -> receiptText(receipt)
        is ReceiptSubject.Person -> personReceiptText(receipt, subject.name)
    }

private fun expiredSentence(subject: ReceiptSubject): String =
    when (subject) {
        ReceiptSubject.Book -> "This book has changed since, so the match can't be undone."
        is ReceiptSubject.Person -> "${subject.name} has changed since, so the match can't be undone."
    }

/** Focus moves to the receipt when one arrives and when Undo settles — not on every tick of Undoing. */
private fun arrivalKey(state: MatchReceiptUiState): String =
    when (state) {
        MatchReceiptUiState.None -> "none"
        is MatchReceiptUiState.Shown -> "shown:${state.receipt.receiptId}:${state.undoError != null}"
        MatchReceiptUiState.Undone -> "undone"
        MatchReceiptUiState.Expired -> "expired"
    }
