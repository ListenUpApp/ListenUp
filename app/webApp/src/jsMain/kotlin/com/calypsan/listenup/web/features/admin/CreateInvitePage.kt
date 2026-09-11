package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.CreateInviteErrorType
import com.calypsan.listenup.client.presentation.admin.CreateInviteField
import com.calypsan.listenup.client.presentation.admin.CreateInviteStatus
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Panel
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Mint an invite for someone to join the library.
 *
 * ⛔ Until this page, an admin on the web could *revoke* an invite but never create one — the
 * People page listed pending invites and offered no way to make one. On a server whose
 * registration policy is closed, that meant the browser could not add a person at all.
 */
@Composable
fun CreateInvitePage(
    state: CreateInviteUiState,
    onCreate: (email: String, role: String, expiresInDays: Int) -> Unit,
    onClearError: () -> Unit,
    onCreateAnother: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("inv") }) {
        Breadcrumb(trail = listOf("People", "Invite"), onNavigate = { onOpenAdmin() })

        val ready = state as? CreateInviteUiState.Ready
        val status = ready?.status
        if (status is CreateInviteStatus.Success) {
            InviteMade(status.invite, onCopy, onCreateAnother, onOpenAdmin)
            return@Div
        }

        H1(attrs = { classes("inv-t") }) { Text("Invite someone") }
        P(attrs = { classes("inv-lede") }) {
            Text("Create an invite to share with someone who wants to join your audiobook library.")
        }
        InviteForm(status, onCreate, onClearError)
    }
}

@Composable
private fun InviteForm(
    status: CreateInviteStatus?,
    onCreate: (String, String, Int) -> Unit,
    onClearError: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ROLE_MEMBER) }
    var days by remember { mutableStateOf(DEFAULT_EXPIRY_DAYS) }

    val error = (status as? CreateInviteStatus.Error)?.type
    val submitting = status is CreateInviteStatus.Submitting

    Panel(title = "Who's joining") {
        val emailProblem = emailError(error)
        Field(
            label = "Email address",
            value = email,
            // ⛔ `Email`, not `Text`, and it is load-bearing beyond the keyboard it summons: the
            // browser's own value sanitisation strips leading and trailing whitespace from an
            // email input, which is what keeps a pasted "  ada@example.com  " from reaching the
            // server as an address it will reject. A `.trim()` here on top of it was dead code —
            // sabotage could not make it observable — so the spec pins the type instead.
            type = InputType.Email,
            autocomplete = "email",
            error = emailProblem != null,
            onInput = { typed ->
                email = typed
                // The typed error is about the value that was submitted; the moment it changes,
                // the error is describing something that is no longer on screen.
                if (error != null) onClearError()
            },
        )
        emailProblem?.let { message -> P(attrs = { classes("inv-err") }) { Text(message) } }
    }

    Panel(title = "Access level") {
        Div(attrs = { classes("inv-opts") }) {
            RoleOption(ROLE_MEMBER, "Member", "Can access the library", role) { role = it }
            RoleOption(ROLE_ADMIN, "Admin", "Can manage users and invites", role) { role = it }
        }
    }

    Panel(title = "Invite expires in") {
        Div(attrs = { classes("inv-days") }) {
            EXPIRY_OPTIONS.forEach { option ->
                Button(attrs = {
                    classes("pill")
                    if (option == days) classes("on")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    attr("aria-pressed", (option == days).toString())
                    onClick { days = option }
                }) { Text(expiryLabel(option)) }
            }
        }
    }

    otherError(error)?.let { message -> P(attrs = { classes("inv-err") }) { Text(message) } }

    Button(attrs = {
        classes("btn-c", "inv-go")
        attr(ATTR_TYPE, VALUE_BUTTON)
        // ⛔ A blank email cannot make an invite, and the server would answer with a validation
        // error the reader has to read to learn what the form already knew.
        if (submitting || email.isBlank()) attr("disabled", "")
        onClick { onCreate(email, role, days) }
    }) { Text(if (submitting) "Creating…" else "Create invite") }
}

@Composable
private fun RoleOption(
    value: String,
    title: String,
    subtitle: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Button(attrs = {
        classes("inv-opt")
        if (value == selected) classes("on")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-pressed", (value == selected).toString())
        onClick { onSelect(value) }
    }) {
        Span(attrs = { classes("inv-opt-t") }) { Text(title) }
        Span(attrs = { classes("inv-opt-s") }) { Text(subtitle) }
    }
}

/**
 * The payoff: the link, and the two things you might do next.
 *
 * ⛔ The URL is rendered in full and selectable, not hidden behind the Copy button. `navigator
 * .clipboard` needs a secure context, and a self-hosted server reached over plain http on a LAN is
 * exactly the common case where it is missing — a page whose only way out was a button that
 * silently does nothing would strand the admin with an invite they cannot convey.
 */
@Composable
private fun InviteMade(
    invite: InviteInfo,
    onCopy: (String) -> Unit,
    onCreateAnother: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    Div(attrs = { classes("inv-done") }) {
        Span(attrs = { classes("inv-done-e") }) { Text("Invite created") }
        H1(attrs = { classes("inv-t") }) { Text(invite.email) }
        P(attrs = { classes("inv-lede") }) { Text("Send them this link. It expires on its own if nobody uses it.") }
        Div(attrs = { classes("inv-link") }) {
            Span(attrs = { classes("inv-url") }) { Text(invite.url) }
            Button(attrs = {
                classes("btn-o", "inv-copy")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onCopy(invite.url) }
            }) { Text("Copy") }
        }
        Div(attrs = { classes("inv-actions") }) {
            Button(attrs = {
                classes("btn-c")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onOpenAdmin() }
            }) { Text("Done") }
            Button(attrs = {
                classes("btn-o")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onCreateAnother() }
            }) { Text("Create another") }
        }
    }
}

/** The email field's own error, which is the only one that belongs on a field. */
internal fun emailError(type: CreateInviteErrorType?): String? =
    when {
        type is CreateInviteErrorType.EmailInUse -> {
            "Somebody with that address is already here."
        }

        type is CreateInviteErrorType.ValidationError && type.field == CreateInviteField.EMAIL -> {
            "That does not look like an email address."
        }

        else -> {
            null
        }
    }

/**
 * Everything that is not about the email field.
 *
 * ⛔ Returns null for the field errors rather than repeating them: a message shown twice, once
 * under the input and once above the button, reads as two separate problems.
 */
internal fun otherError(type: CreateInviteErrorType?): String? =
    when (type) {
        null, is CreateInviteErrorType.EmailInUse -> {
            null
        }

        is CreateInviteErrorType.ValidationError -> {
            if (type.field == CreateInviteField.EMAIL) null else "Check the form and try again."
        }

        is CreateInviteErrorType.NetworkError -> {
            type.detail ?: "Couldn't reach the server. Check your connection and try again."
        }

        is CreateInviteErrorType.ServerError -> {
            type.detail ?: "The server couldn't create that invite."
        }
    }

internal fun expiryLabel(days: Int): String = if (days == 1) "1 day" else "$days days"

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val ROLE_MEMBER = "member"

private const val ROLE_ADMIN = "admin"

private const val DEFAULT_EXPIRY_DAYS = 7

private val EXPIRY_OPTIONS = listOf(1, 7, 30)
