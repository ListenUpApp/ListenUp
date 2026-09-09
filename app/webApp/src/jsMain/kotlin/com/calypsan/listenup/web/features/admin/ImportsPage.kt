package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Imports — what has been brought over from Audiobookshelf before, and the way to start another.
 *
 * Pure in [state]; the store wiring lives one level up.
 *
 * **Starting an import is a link, not an action here.** The flow needs a file before it can do
 * anything, and its own `Idle` state is what "ready to accept a file" means — so the picker belongs
 * on that page rather than on this one, where a picked file would have nowhere to go.
 */
@Composable
fun ImportsPage(
    state: ABSImportListUiState,
    onDelete: (ImportSummary) -> Unit,
    onClearError: () -> Unit,
    onRetry: () -> Unit,
    onNewImport: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("imp") }) {
        Button(attrs = {
            classes("btn-o", "imp-back")
            attr("type", VALUE_BUTTON)
            onClick { onOpenAdmin() }
        }) { Text("← Admin") }

        H1(attrs = { classes("imp-title") }) { Text("Imports") }
        P(attrs = { classes("imp-sub") }) {
            Text("Bring listening history over from an Audiobookshelf backup.")
        }

        when (state) {
            ABSImportListUiState.Loading -> {
                Div(attrs = { classes("skel", "imp-skel") })
            }

            is ABSImportListUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("Imports can't be shown") }
                    P { Text(state.error.message) }
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", VALUE_BUTTON)
                        onClick { onRetry() }
                    }) { Text("Try again") }
                }
            }

            is ABSImportListUiState.Ready -> {
                ReadyContent(state, onDelete, onClearError, onNewImport)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: ABSImportListUiState.Ready,
    onDelete: (ImportSummary) -> Unit,
    onClearError: () -> Unit,
    onNewImport: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ImportSummary?>(null) }

    state.error?.let { error ->
        ImportNotice(error.message, onClearError)
    }

    Div(attrs = { classes("imp-bar") }) {
        Span(attrs = { classes("imp-count") }) { Text(importSummaryLabel(state.imports.size)) }
        Button(attrs = {
            classes("btn-c", "imp-new")
            attr("type", VALUE_BUTTON)
            onClick { onNewImport() }
        }) { Text("New import") }
    }

    if (state.imports.isEmpty()) {
        Div(attrs = { classes("empty") }) {
            H3 { Text("Nothing imported yet") }
            P {
                Text(
                    "An import reads an Audiobookshelf backup and writes its listening history onto the matching books here.",
                )
            }
        }
    } else {
        Div(attrs = { classes("imp-list") }) {
            state.imports.forEach { summary ->
                ImportRow(summary) { pendingDelete = summary }
            }
        }
    }

    val pending = pendingDelete
    ConfirmDialog(
        open = pending != null,
        title = "Delete this import?",
        // Deleting the record does not undo the history it wrote — saying so is the whole point,
        // because "delete import" reads like "undo import" and it is not.
        body =
            "The staged import is removed from this list. Any history it already wrote stays where " +
                "it is — deleting the record does not undo the import.",
        confirmLabel = "Delete",
        onConfirm = {
            pending?.let(onDelete)
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    )
}

@Composable
private fun ImportRow(
    summary: ImportSummary,
    onAskDelete: () -> Unit,
) {
    Div(attrs = { classes("imp-row") }) {
        Div(attrs = { classes("imp-row-t") }) {
            Span(attrs = { classes("imp-when") }) { Text(formatWhen(summary.createdAt)) }
            Span(attrs = { classes("imp-what") }) {
                Text("${countLabel(summary.bookCount, "book")} · ${countLabel(summary.userCount, "listener")}")
            }
        }
        Span(attrs = { classes("imp-status") }) { Text(summary.status.name.lowercase()) }
        Button(attrs = {
            classes("iconbtn", "imp-del")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Delete the import from ${formatWhen(summary.createdAt)}")
            attr("title", "Delete")
            disabledWhen(false)
            onClick { onAskDelete() }
        }) { Icon(WebIcon.Trash, size = SMALL_ICON) }
    }
}

/** A dismissible line above the list: something the last action failed to do. */
@Composable
internal fun ImportNotice(
    message: String,
    onDismiss: () -> Unit,
) {
    Div(attrs = { classes("imp-err") }) {
        P(attrs = {
            classes("imp-err-t")
            attr("role", "alert")
        }) { Text(message) }
        Button(attrs = {
            classes("imp-err-x")
            attr("type", VALUE_BUTTON)
            attr("aria-label", "Dismiss")
            onClick { onDismiss() }
        }) { Icon(WebIcon.X, size = SMALL_ICON) }
    }
}

internal fun countLabel(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"

private fun importSummaryLabel(count: Int): String = countLabel(count, "import")

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16
