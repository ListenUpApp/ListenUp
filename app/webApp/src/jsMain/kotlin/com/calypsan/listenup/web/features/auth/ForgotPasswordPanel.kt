package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PasswordField
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Recovering an account whose password is gone. Pure in [state].
 *
 * The flow is deliberately not the emailed-link one the web has trained everyone to expect: a
 * self-hosted ListenUp server has no mail relay to send from, so recovery goes through the
 * server's admin instead. Request, wait for them to approve, then finish with a code they convey
 * out of band. That is why the middle of this panel is a waiting room rather than "check your
 * inbox" — the copy has to say what actually happens, or a listener sits watching an inbox
 * nothing will ever arrive in.
 *
 * The field values live here rather than per-state, so they survive the state changes that happen
 * underneath them — an address typed in [ForgotPasswordUiState.EnterEmail] must still be there
 * during [ForgotPasswordUiState.Submitting], which is a different state object entirely.
 */
@Composable
fun ForgotPasswordPanel(
    state: ForgotPasswordUiState,
    onRequestReset: (email: String) -> Unit,
    onCompleteReset: (code: String, newPassword: String) -> Unit,
    onCheckStatus: () -> Unit,
    onRetryRequest: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    when (state) {
        ForgotPasswordUiState.EnterEmail,
        ForgotPasswordUiState.Submitting,
        -> {
            RequestStep(submitting = state is ForgotPasswordUiState.Submitting, onRequestReset, onBackToSignIn)
        }

        is ForgotPasswordUiState.AwaitingApproval -> {
            WaitingStep(onCheckStatus, onBackToSignIn)
        }

        is ForgotPasswordUiState.EnterCode -> {
            CodeStep(state, onCompleteReset, onBackToSignIn)
        }

        ForgotPasswordUiState.Denied -> {
            OutcomeStep(
                message = "Your server's admin declined this request.",
                isError = true,
                actionLabel = "Ask again",
                actionIcon = WebIcon.Lock,
                onAction = onRetryRequest,
                onBackToSignIn = onBackToSignIn,
            )
        }

        ForgotPasswordUiState.Complete -> {
            OutcomeStep(
                message = "Your password is set. Sign in with it.",
                isError = false,
                actionLabel = "Sign in",
                actionIcon = WebIcon.LogIn,
                onAction = onBackToSignIn,
                onBackToSignIn = null,
            )
        }

        is ForgotPasswordUiState.Error -> {
            OutcomeStep(
                message = state.message,
                isError = true,
                actionLabel = "Try again",
                actionIcon = WebIcon.Lock,
                onAction = onRetryRequest,
                onBackToSignIn = onBackToSignIn,
            )
        }
    }
}

/** Step one: the address to recover. */
@Composable
private fun RequestStep(
    submitting: Boolean,
    onRequestReset: (email: String) -> Unit,
    onBackToSignIn: () -> Unit,
) {
    var email by remember { mutableStateOf("") }

    // A real <form> for the same reason LoginForm uses one: Enter in a text field submits the form
    // it is in, and no keydown listener reproduces that — implicit submission is driven by real
    // user input. preventDefault stops the browser's own navigation, which here would reload the
    // page and take the Kotlin/JS runtime with it.
    Form(attrs = {
        classes(FIELDS_CLASS)
        // `this.` is load-bearing: the parameter list has no `onSubmit`, but keeping the same
        // shape as every other form here means a later parameter cannot silently shadow it.
        this.onSubmit { event ->
            event.preventDefault()
            onRequestReset(email)
        }
    }) {
        P { Text("We'll ask this server's admin to approve a reset. They'll give you a code to finish with.") }

        Field(
            label = "Email",
            value = email,
            onInput = { email = it },
            leading = WebIcon.Mail,
            placeholder = "you@example.com",
            type = InputType.Email,
            id = RESET_EMAIL_ID,
            autocomplete = "username",
        )

        Button(attrs = {
            classes("btn")
            attr("type", "submit")
            if (submitting) disabled()
        }) {
            Icon(WebIcon.Lock, size = BUTTON_ICON_SIZE)
            Text(if (submitting) "Asking…" else "Ask for a reset")
        }

        BackToSignIn(onBackToSignIn)
    }
}

/**
 * Step two: the waiting room.
 *
 * The manual re-check is not decoration. The status watch is a socket, and a socket can die
 * without saying so — the same Never Stranded reasoning [PendingApprovalPanel] carries. The
 * ViewModel also polls, but a listener staring at an unchanging page needs to be able to ask.
 */
@Composable
private fun WaitingStep(
    onCheckStatus: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    Div(attrs = { classes(FIELDS_CLASS) }) {
        P {
            Text(
                "Waiting for an admin to approve your reset. They'll pass you a code — this page updates on its own once they do.",
            )
        }

        Button(attrs = {
            classes("btn-ghost")
            attr("type", "button")
            onClick { onCheckStatus() }
        }) {
            Icon(WebIcon.Clock, size = BUTTON_ICON_SIZE)
            Text("Check again")
        }

        BackToSignIn(onBackToSignIn)
    }
}

/**
 * Step three: the admin's code, plus the new password.
 *
 * A wrong code is not terminal — the shared ViewModel keeps the screen here and reports the
 * remaining budget, so the form stays on screen with the count beside it. [edited] follows
 * [LoginForm]'s rule: the moment a field is touched, the message describing the previous value
 * goes, because a field asserting something false about its own contents is worse than silence.
 */
@Composable
private fun CodeStep(
    state: ForgotPasswordUiState.EnterCode,
    onCompleteReset: (code: String, newPassword: String) -> Unit,
    onBackToSignIn: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var edited by remember(state) { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    val serverError = state.error.takeUnless { edited }

    Form(attrs = {
        classes(FIELDS_CLASS)
        this.onSubmit { event ->
            event.preventDefault()
            // Checked here rather than by the ViewModel: `completeReset` takes one password, so
            // the confirm field has no other reader. Refusing locally also spares a live ticket
            // one of its finite attempts on a typo the user can see for themselves.
            if (password != confirm) {
                mismatch = true
            } else {
                mismatch = false
                onCompleteReset(code, password)
            }
        }
    }) {
        P { Text("Approved. Enter the code your admin gave you and choose a new password.") }

        Field(
            label = "Code",
            value = code,
            onInput = {
                code = it
                edited = true
            },
            leading = WebIcon.Hash,
            error = serverError != null,
            id = RESET_CODE_ID,
            autocomplete = "one-time-code",
        )
        PasswordField(
            label = "New password",
            value = password,
            onInput = {
                password = it
                mismatch = false
            },
            id = RESET_PASSWORD_ID,
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
            id = RESET_CONFIRM_ID,
            autocomplete = "new-password",
        )

        if (mismatch) {
            Div(attrs = { classes("auth-err") }) { Text("The two passwords do not match.") }
        } else {
            serverError?.let { message ->
                Div(attrs = { classes("auth-err") }) {
                    Text(message)
                    state.attemptsRemaining?.let { remaining ->
                        Text(" ")
                        Text(attemptsMessage(remaining))
                    }
                }
            }
        }

        Button(attrs = {
            classes("btn")
            attr("type", "submit")
        }) {
            Icon(WebIcon.Check, size = BUTTON_ICON_SIZE)
            Text("Set new password")
        }

        BackToSignIn(onBackToSignIn)
    }
}

/** A terminal state: one line of copy, one way forward, and optionally a way back. */
@Composable
private fun OutcomeStep(
    message: String,
    isError: Boolean,
    actionLabel: String,
    actionIcon: WebIcon,
    onAction: () -> Unit,
    onBackToSignIn: (() -> Unit)?,
) {
    Div(attrs = { classes(FIELDS_CLASS) }) {
        if (isError) {
            Div(attrs = { classes("auth-err") }) { Text(message) }
        } else {
            P { Text(message) }
        }

        Button(attrs = {
            classes("btn")
            attr("type", "button")
            onClick { onAction() }
        }) {
            Icon(actionIcon, size = BUTTON_ICON_SIZE)
            Text(actionLabel)
        }

        onBackToSignIn?.let { BackToSignIn(it) }
    }
}

@Composable
private fun BackToSignIn(onBackToSignIn: () -> Unit) {
    Div(attrs = { classes("auth-alt") }) {
        Span(attrs = {
            classes("lnk")
            onClick { onBackToSignIn() }
        }) { Text("Back to sign in") }
    }
}

/** Singular/plural without a formatter, because "1 tries left" reads as a bug in the app. */
private fun attemptsMessage(remaining: Int): String = if (remaining == 1) "1 try left." else "$remaining tries left."

/** The column every step of this flow sits in — four occurrences, so it earns a name. */
private const val FIELDS_CLASS = "auth-fields"

internal const val RESET_EMAIL_ID = "auth-reset-email"

internal const val RESET_CODE_ID = "auth-reset-code"

internal const val RESET_PASSWORD_ID = "auth-reset-password"

internal const val RESET_CONFIRM_ID = "auth-reset-confirm"

private const val BUTTON_ICON_SIZE = 19
