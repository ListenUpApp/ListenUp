package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PasswordField
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Redeeming an invite: look up a code, see who is inviting you where, then join. Pure in [state].
 *
 * Web reaches [com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel] through
 * `onCodeEntered` and never through `start(serverUrl, …)`, which is the deep-link entry the native
 * clients need. They need it because a tapped Universal Link can arrive at a fresh install with no
 * server configured at all, so the link has to carry one — and then be probed, because the address
 * the admin sees on their LAN may not be the address the invitee can reach. A browser has none of
 * that problem: this page was *served by* the server, `Main.kt` seeds [ServerConfig] from the page
 * origin before anything mounts, and an origin the browser just loaded from is reachable by
 * construction. So the one platform where the whole reachability dance is unnecessary skips it.
 */
@Composable
fun ClaimInvitePanel(
    state: ClaimInviteUiState,
    onCodeEntered: (code: String) -> Unit,
    onClaim: (password: String, firstName: String, lastName: String) -> Unit,
    onBackToSignIn: () -> Unit,
) {
    when (state) {
        ClaimInviteUiState.Idle,
        ClaimInviteUiState.LookingUp,
        -> {
            CodeStep(lookingUp = state is ClaimInviteUiState.LookingUp, onCodeEntered, onBackToSignIn)
        }

        is ClaimInviteUiState.Preview -> {
            // An invite the server has already rejected is an error wearing a preview's shape —
            // rendering the join form over it would invite someone to fill in four fields for a
            // request that cannot succeed.
            if (state.preview.valid) {
                ClaimStep(state.preview, submitting = false, onClaim, onBackToSignIn)
            } else {
                DeadEnd(state.preview.invalidReason ?: INVITE_NO_LONGER_VALID, onBackToSignIn)
            }
        }

        ClaimInviteUiState.Submitting -> {
            // Deliberately not a spinner: the ViewModel drops the preview on the way through
            // Submitting, and swapping a filled-in form for a loading state loses what the reader
            // typed if the claim comes back a failure. The form stays, with its button held.
            ClaimStep(preview = null, submitting = true, onClaim, onBackToSignIn)
        }

        ClaimInviteUiState.Claimed -> {
            // Terminal, and momentary: the claim persists a session, so `AuthState` flips to
            // Authenticated and the gate replaces this whole branch. One honest line covers the
            // frame or two before that lands.
            Div(attrs = { classes(CLAIM_FIELDS_CLASS) }) { P { Text("You're in. Loading your library…") } }
        }

        is ClaimInviteUiState.Error -> {
            DeadEnd(state.message, onBackToSignIn)
        }
    }
}

/** Step one: the code itself, for someone who was told it rather than sent a link. */
@Composable
private fun CodeStep(
    lookingUp: Boolean,
    onCodeEntered: (String) -> Unit,
    onBackToSignIn: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Form(attrs = {
        classes(CLAIM_FIELDS_CLASS)
        this.onSubmit { event ->
            event.preventDefault()
            if (code.isNotBlank()) onCodeEntered(code.trim())
        }
    }) {
        P { Text("Enter the invite code you were given and we'll show you who it's from.") }

        Field(
            label = "Invite code",
            value = code,
            onInput = { code = it },
            leading = WebIcon.Hash,
            id = INVITE_CODE_ID,
            autocomplete = "off",
        )

        Button(attrs = {
            classes("btn")
            attr("type", "submit")
            // Blank is not a lookup. The ViewModel would happily ask the server about "", and the
            // answer would be an error the reader caused by pressing a button that should not have
            // been pressable.
            if (lookingUp || code.isBlank()) disabled()
        }) {
            Icon(WebIcon.ChevronRight, size = CLAIM_ICON_SIZE)
            Text(if (lookingUp) "Looking up…" else "Continue")
        }

        ClaimBackLink(onBackToSignIn)
    }
}

/**
 * Step two: who invited you, where, and the account you are about to make.
 *
 * [preview] is null while the claim is in flight — see [ClaimInvitePanel]'s Submitting branch. The
 * heading and the invite card go with it; the fields and their typed values do not, because
 * `remember` here outlives the state change that dropped the preview.
 */
@Composable
private fun ClaimStep(
    preview: InvitePreview?,
    submitting: Boolean,
    onClaim: (password: String, firstName: String, lastName: String) -> Unit,
    onBackToSignIn: () -> Unit,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    Form(attrs = {
        classes(CLAIM_FIELDS_CLASS)
        this.onSubmit { event ->
            event.preventDefault()
            // Local, like every other new-password form here: `claimInvite` takes one password, so
            // the confirmation has no other reader, and a typo caught on this side never burns a
            // one-time code.
            if (password != confirm) {
                mismatch = true
            } else {
                mismatch = false
                onClaim(password, firstName, lastName)
            }
        }
    }) {
        preview?.let { InviteCard(it) }

        Div(attrs = { classes("auth-row") }) {
            Field(
                label = "First name",
                value = firstName,
                onInput = { firstName = it },
                id = INVITE_FIRST_ID,
                autocomplete = "given-name",
            )
            Field(
                label = "Last name",
                value = lastName,
                onInput = { lastName = it },
                id = INVITE_LAST_ID,
                autocomplete = "family-name",
            )
        }
        Div(attrs = { classes("auth-row") }) {
            PasswordField(
                label = "Password",
                value = password,
                onInput = {
                    password = it
                    mismatch = false
                },
                id = INVITE_PASSWORD_ID,
                autocomplete = "new-password",
            )
            PasswordField(
                label = "Confirm",
                value = confirm,
                onInput = {
                    confirm = it
                    mismatch = false
                },
                error = mismatch,
                id = INVITE_CONFIRM_ID,
                autocomplete = "new-password",
            )
        }

        if (mismatch) {
            Div(attrs = { classes("auth-err") }) { Text("The two passwords do not match.") }
        }

        Button(attrs = {
            classes("btn")
            attr("type", "submit")
            if (submitting) disabled()
        }) {
            Icon(WebIcon.UserPlus, size = CLAIM_ICON_SIZE)
            Text(if (submitting) "Joining…" else "Join")
        }

        ClaimBackLink(onBackToSignIn)
    }
}

/**
 * Who is inviting you, and to what.
 *
 * The names are the point. An invite code is an opaque token, and a page that asks for a password
 * without saying whose library it is for is indistinguishable from a phishing form.
 */
@Composable
private fun InviteCard(preview: InvitePreview) {
    Div(attrs = { classes("inv-card") }) {
        P(attrs = { classes("inv-lead") }) {
            Span(attrs = { classes("inv-who") }) { Text(preview.invitedByName) }
            Text(" invited you to ")
            Span(attrs = { classes("inv-who") }) { Text(preview.serverName) }
            Text(".")
        }
        P(attrs = { classes("inv-mail") }) {
            Text("You'll sign in as ")
            Span(attrs = { classes("mono") }) { Text(preview.email) }
            Text(".")
        }
    }
}

/** A code that cannot be redeemed. One message, and the way back. */
@Composable
private fun DeadEnd(
    message: String,
    onBackToSignIn: () -> Unit,
) {
    Div(attrs = { classes(CLAIM_FIELDS_CLASS) }) {
        Div(attrs = { classes("auth-err") }) { Text(message) }
        ClaimBackLink(onBackToSignIn)
    }
}

@Composable
private fun ClaimBackLink(onBackToSignIn: () -> Unit) {
    Div(attrs = { classes("auth-alt") }) {
        Span(attrs = {
            classes("lnk")
            onClick { onBackToSignIn() }
        }) { Text("Back to sign in") }
    }
}

/** Fallback for a server that rejects an invite without saying why. */
private const val INVITE_NO_LONGER_VALID =
    "This invite is no longer valid. It may have already been used, or it may have expired."

/** The column every step of this flow sits in — five occurrences, so it earns a name. */
private const val CLAIM_FIELDS_CLASS = "auth-fields"

internal const val INVITE_CODE_ID = "auth-invite-code"

internal const val INVITE_FIRST_ID = "auth-invite-first"

internal const val INVITE_LAST_ID = "auth-invite-last"

internal const val INVITE_PASSWORD_ID = "auth-invite-password"

internal const val INVITE_CONFIRM_ID = "auth-invite-confirm"

private const val CLAIM_ICON_SIZE = 19
