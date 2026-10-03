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
import com.calypsan.listenup.web.design.FocusHold
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PasswordField
import com.calypsan.listenup.web.design.SwitchField
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.focusLanding
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
    val announcement = rememberTokenAnnouncement(token, tokenSave, replacing)
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
        // Save, Replace and Remove each replace the control they were pressed on: focus lands on the
        // saved line, or on the field, rather than falling to the top of the page.
        FocusHold(key = token to replacing) {
            if (token is HardcoverApiTokenStatus.Saved && !replacing) {
                SavedToken(
                    username = token.username,
                    busy = busy,
                    onReplace = { replacing = true },
                    onRemove = { confirmingRemove = true },
                )
            } else {
                TokenEntry(
                    rejected = token is HardcoverApiTokenStatus.Rejected,
                    draft = draft,
                    tokenSave = tokenSave,
                    onDraft = {
                        draft = it
                        if (tokenSave is HardcoverTokenSave.Refused) onClearTokenError()
                    },
                    onSave = { onSaveToken(draft) },
                )
            }
        }
        // Checking, refused and saved, said where a screen reader is listening: one polite region,
        // on the page before any of them, so each change of its words is heard.
        P(attrs = {
            classes(SR_ONLY)
            attr("role", "status")
        }) { Text(announcement) }
        A(href = HARDCOVER_API_PAGE, attrs = {
            classes("srv-ext")
            attr("target", "_blank")
            attr("rel", "noopener noreferrer")
        }) {
            Text("Get a token from Hardcover")
            Span(attrs = { classes(SR_ONLY) }) { Text(" (opens in a new tab)") }
            Icon(WebIcon.External, size = LINK_ICON)
        }
        Div(attrs = { classes("srv-toggle") }) {
            Span(attrs = {
                classes("srv-toggle-d")
                id(METADATA_NOTE_ID)
            }) {
                Text(
                    if (status.metadataUnavailable != null) {
                        "Add an API token or connect a Hardcover account to enable"
                    } else {
                        "Offer Hardcover's moods, genres, series and descriptions when you match a book"
                    },
                )
            }
            SwitchField(
                label = "Hardcover metadata",
                checked = status.metadataEnabled,
                onChange = onMetadata,
                describedBy = METADATA_NOTE_ID,
            )
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

/** A saved token: whose it is — where focus lands once it saves — with Replace and Remove. */
@Composable
private fun SavedToken(
    username: String,
    busy: Boolean,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    Div(attrs = { classes("srv-toggle") }) {
        Div(attrs = {
            classes("srv-toggle-d")
            focusLanding()
        }) { Text("Set · belongs to @$username") }
        Div(attrs = { classes("srv-toggle-acts") }) {
            // The visible word first, then what it acts on (WCAG 2.5.3, 2.4.6).
            Button(kind = ButtonKind.Secondary, size = ButtonSize.Sm, onClick = onReplace) {
                Text("Replace")
                Span(attrs = { classes(SR_ONLY) }) { Text(ACTS_ON) }
            }
            Button(kind = ButtonKind.Danger, size = ButtonSize.Sm, pressable = !busy, onClick = onRemove) {
                Text("Remove")
                Span(attrs = { classes(SR_ONLY) }) { Text(ACTS_ON) }
            }
        }
    }
}

/**
 * The token field and Save — where Replace, and a removed token, put focus: the next thing to do is
 * type one. Save stays pressable rather than disabled while Hardcover checks, so it keeps the focus
 * that pressed it.
 */
@Composable
private fun TokenEntry(
    rejected: Boolean,
    draft: String,
    tokenSave: HardcoverTokenSave,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
) {
    val busy = tokenSave == HardcoverTokenSave.Busy
    if (rejected) {
        P(attrs = {
            classes("srv-hint")
            attr("role", "alert")
        }) { Text("Hardcover rejected this token — replace it") }
    }
    Div(attrs = {
        style { property("display", "contents") }
        focusLanding()
    }) {
        PasswordField(
            label = "API token",
            value = draft,
            onInput = onDraft,
            id = HARDCOVER_TOKEN_FIELD_ID,
            autocomplete = "off",
            errorText = (tokenSave as? HardcoverTokenSave.Refused)?.error?.message,
        )
    }
    Button(kind = ButtonKind.Primary, enabled = draft.isNotBlank() || busy, pressable = !busy, onClick = onSave) {
        Text(if (busy) CHECKING else "Save token")
    }
}

/**
 * What the token's status region says. Empty until something happens, so loading the page announces
 * nothing; then "Checking with Hardcover…", the refusal, and "Saved — belongs to @…" or "API token removed"
 * as each arrives.
 */
@Composable
private fun rememberTokenAnnouncement(
    token: HardcoverApiTokenStatus,
    tokenSave: HardcoverTokenSave,
    replacing: Boolean,
): String {
    var said by remember { mutableStateOf("") }
    var inFlight by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tokenSave, token) {
        val saved = token as? HardcoverApiTokenStatus.Saved
        when {
            tokenSave == HardcoverTokenSave.Busy -> {
                val doing = inFlight ?: if (saved != null && !replacing) REMOVING else CHECKING
                inFlight = doing
                said = doing
            }

            tokenSave is HardcoverTokenSave.Refused -> {
                inFlight = null
                said = tokenSave.error.message
            }

            // The answer arrives with the new status in one update, so what it came to is read off it.
            inFlight == CHECKING -> {
                inFlight = null
                said = saved?.let { "Saved — belongs to @${it.username}" }.orEmpty()
            }

            inFlight == REMOVING -> {
                inFlight = null
                said = if (saved == null) "API token removed" else ""
            }
        }
    }
    return said
}

private const val CHECKING = "Checking with Hardcover…"
private const val SR_ONLY = "sr-only"

/** What Replace and Remove act on, said after their visible word. */
private const val ACTS_ON = " API token"
private const val REMOVING = "Removing the API token…"
private const val METADATA_NOTE_ID = "hc-metadata-note"
private const val LINK_ICON = 14
