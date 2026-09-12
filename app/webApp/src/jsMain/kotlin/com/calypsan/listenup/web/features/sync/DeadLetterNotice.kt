package com.calypsan.listenup.web.features.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.sync.PendingOperationUi
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The edits that did not make it, and the two things to do about each.
 *
 * ⛔ Renders **nothing at all** when there are none — see [DeadLetterSession] for why this is the
 * one part of the sync queue web surfaces. A reader with no failed edits has nothing to decide, and
 * a permanently-present "sync is fine" indicator is exactly the chrome this client decided against.
 *
 * ⛔ Not dismissible. Closing the dialog leaves the notice standing, because the failures are still
 * there: the only ways out are retrying (which may succeed) and dismissing (which accepts the
 * server's version). A close button would let a reader hide the fact that their edit is gone.
 */
@Composable
fun DeadLetterNotice(
    failed: List<PendingOperationUi>,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRetryAll: () -> Unit,
    onDismissAll: () -> Unit,
) {
    if (failed.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    Div(attrs = {
        classes("dlq")
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        Div(attrs = { classes("dlq-ico") }) { Icon(WebIcon.EyeOff, size = DLQ_ICON) }
        Span(attrs = { classes("dlq-t") }) { Text(noticeText(failed.size)) }
        Button(attrs = {
            classes(QUIET, "dlq-go")
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { open = true }
        }) { Text("Review") }
    }

    if (open) {
        ModalDialog(open = true, title = "Changes that didn't save", onDismiss = { open = false }) {
            P(attrs = { classes("dlg-p") }) {
                Text(
                    "The server refused these after several attempts. Retrying sends the change again; " +
                        "dismissing keeps the server's version instead.",
                )
            }
            Div(attrs = { classes("dlq-list") }) {
                failed.forEach { op -> FailedRow(op, onRetry, onDismiss) }
            }
            Div(attrs = { classes("dlg-actions") }) {
                Button(attrs = {
                    classes(QUIET)
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onDismissAll() }
                }) { Text("Dismiss all") }
                Button(attrs = {
                    classes("btn-c")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onRetryAll() }
                }) { Text("Retry all") }
            }
        }
    }
}

/**
 * One failed edit: what it was, what the server said, and the two answers.
 *
 * The server's own message is shown verbatim when there is one. It is the only thing that explains
 * why retrying might behave differently this time, and substituting house copy for it would leave
 * the reader choosing between Retry and Dismiss with nothing to choose on.
 */
@Composable
private fun FailedRow(
    op: PendingOperationUi,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Div(attrs = { classes("dlq-row") }) {
        Div(attrs = { classes("dlq-what") }) {
            Span(attrs = { classes("dlq-desc") }) { Text(op.description) }
            op.error?.let { reason -> Span(attrs = { classes("dlq-why") }) { Text(reason) } }
        }
        Div(attrs = { classes("dlq-acts") }) {
            Button(attrs = {
                classes(QUIET)
                attr(ATTR_TYPE, VALUE_BUTTON)
                attr("aria-label", "Retry ${op.description}")
                onClick { onRetry(op.id) }
            }) { Text("Retry") }
            Button(attrs = {
                classes(QUIET)
                attr(ATTR_TYPE, VALUE_BUTTON)
                attr("aria-label", "Dismiss ${op.description}")
                onClick { onDismiss(op.id) }
            }) { Text("Dismiss") }
        }
    }
}

/** How many edits are waiting on a decision. */
internal fun noticeText(count: Int): String = if (count == 1) "1 change didn't save" else "$count changes didn't save"

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val QUIET = "btn-o"

private const val DLQ_ICON = 18
