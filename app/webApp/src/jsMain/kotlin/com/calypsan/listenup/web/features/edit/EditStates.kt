package com.calypsan.listenup.web.features.edit

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * An edit page whose subject could not be loaded: what happened, the way back, and Try again when
 * asking again could help ([AppError.isRetryable]). Never an empty form that looks editable — its
 * Save would write blanks over a record that was never read.
 */
@Composable
fun EditLoadFailed(
    error: AppError,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        title = "Couldn't open this for editing",
        body = error.message,
        look = EmptyLook.Page,
        marker = "edit-load-failed",
        announce = true,
    ) {
        if (onRetry != null && error.isRetryable) {
            Button(
                kind = ButtonKind.Primary,
                onClick = onRetry,
                attrs = { classes("edit-retry") },
            ) { Text("Try again") }
        }
        Button(kind = ButtonKind.Secondary, onClick = onBack, attrs = { classes("edit-back") }) { Text("Back") }
    }
}

/**
 * A Save, merge or image failure above the form it belongs to. Dismissible rather than fatal: the
 * form still holds the reader's edits, and throwing the page away over a failed save would throw
 * those away with it.
 */
@Composable
fun EditInlineError(
    error: AppError,
    onDismiss: () -> Unit,
) {
    Div(attrs = {
        classes("edit-error")
        attr("role", "alert")
    }) {
        P { Text(error.message) }
        Button(kind = ButtonKind.Secondary, onClick = onDismiss) { Text("Dismiss") }
    }
}
