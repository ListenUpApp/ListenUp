package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.PasswordField
import com.calypsan.listenup.web.design.SwitchField
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** Hardcover's API page, where an admin gets a token. */
private const val HARDCOVER_API_PAGE = "https://hardcover.app/account/api"

/** The token field's id: one field on the page, so a fixed id. */
internal const val HARDCOVER_TOKEN_FIELD_ID = "hc-token"

/**
 * Admin → Hardcover (#1542): the write-only API token, where to get one, and the "Hardcover metadata"
 * switch. The token lives only in this panel's own state until Save sends it, once; it is cleared when
 * Hardcover accepts it, and the page never shows it again — only whose it is.
 *
 * ⛔ `autocomplete="off"` on the field: an API token is not a site password, and the browser must not
 * offer to save or fill one.
 */
@Composable
internal fun HardcoverSourceSection(
    status: HardcoverSourceStatus,
    tokenSave: HardcoverTokenSave,
    onSaveToken: (String) -> Unit,
    onRemoveToken: () -> Unit,
    onMetadata: (Boolean) -> Unit,
    onClearTokenError: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }
    val token = status.apiToken
    val busy = tokenSave == HardcoverTokenSave.Busy
    LaunchedEffect(token) {
        if (token is HardcoverApiTokenStatus.Saved) {
            draft = ""
            replacing = false
        }
    }

    FormSection(title = "Hardcover") {
        P(attrs = { classes("srv-hint") }) {
            Text(
                "An API token lets ListenUp read Hardcover's catalogue — moods, genres, series and ratings — " +
                    "without borrowing anyone's account. It is never used for anyone's shelves.",
            )
        }
        if (token is HardcoverApiTokenStatus.Saved && !replacing) {
            Div(attrs = { classes("srv-toggle") }) {
                Div(attrs = { classes("srv-toggle-d") }) { Text("Set · belongs to @${token.username}") }
                Button(kind = ButtonKind.Secondary, size = ButtonSize.Sm, onClick = { replacing = true }) { Text("Replace") }
                Button(kind = ButtonKind.Danger, size = ButtonSize.Sm, enabled = !busy, onClick = { confirmingRemove = true }) {
                    Text("Remove")
                }
            }
        } else {
            if (token is HardcoverApiTokenStatus.Rejected) {
                P(attrs = {
                    classes("srv-hint")
                    attr("role", "alert")
                }) { Text("Hardcover rejected this token — replace it") }
            }
            PasswordField(
                label = "API token",
                value = draft,
                onInput = {
                    draft = it
                    if (tokenSave is HardcoverTokenSave.Refused) onClearTokenError()
                },
                id = HARDCOVER_TOKEN_FIELD_ID,
                autocomplete = "off",
                errorText = (tokenSave as? HardcoverTokenSave.Refused)?.error?.message,
            )
            Button(kind = ButtonKind.Primary, enabled = draft.isNotBlank() && !busy, onClick = { onSaveToken(draft) }) {
                Text(if (busy) "Checking with Hardcover…" else "Save token")
            }
        }
        A(href = HARDCOVER_API_PAGE, attrs = {
            attr("target", "_blank")
            attr("rel", "noopener noreferrer")
        }) { Text("Get a token from Hardcover") }
        Div(attrs = { classes("srv-toggle") }) {
            Span(attrs = { classes("srv-toggle-d") }) {
                Text(
                    if (status.metadataUnavailable != null) {
                        "Add an API token or connect a Hardcover account to enable"
                    } else {
                        "Offer Hardcover's moods, genres, series and descriptions when you match a book"
                    },
                )
            }
            SwitchField(label = "Hardcover metadata", checked = status.metadataEnabled, onChange = onMetadata)
        }
    }

    ConfirmDialog(
        open = confirmingRemove,
        title = "Remove the API token?",
        body = "ListenUp will read Hardcover's catalogue through a connected account instead, if anyone has connected one.",
        confirmLabel = "Remove",
        onConfirm = {
            confirmingRemove = false
            onRemoveToken()
        },
        onDismiss = { confirmingRemove = false },
    )
}
