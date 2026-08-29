package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.auth.LoginErrorType
import com.calypsan.listenup.client.presentation.auth.LoginField
import com.calypsan.listenup.client.presentation.auth.LoginUiState
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
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The sign-in form. Pure in [state] — it owns the two field values and nothing else, so a spec can
 * drive every branch without a ViewModel or a server.
 *
 * Errors are read off the typed [LoginErrorType], never off a message string: the body-level
 * `message` on an `AppError` is a per-subtype constant, so substring-matching it is either
 * redundant or wrong.
 */
@Composable
fun LoginForm(
    state: LoginUiState,
    openRegistration: Boolean,
    onSubmit: (email: String, password: String) -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // Whether the listener has touched a field since this state arrived. Keyed on [state], so every
    // new state (including a fresh failure) starts untouched and the red returns.
    //
    // Without it the error outlives what produced it: `state` only changes on submit, so a field
    // flagged invalid stayed red while the listener corrected it, and the message underneath went
    // on describing a value no longer on screen. A field asserting something false about its own
    // contents is worse than no validation at all.
    var edited by remember(state) { mutableStateOf(false) }

    val error = (state as? LoginUiState.Error)?.type?.takeUnless { edited }
    val badField = (error as? LoginErrorType.ValidationError)?.field

    val submit = onSubmit

    // A real <form>, not a Div with a click handler. Enter in a text field submits the form it is
    // in — that is a browser behaviour we get for free, and cannot reproduce by hand: it is driven
    // by real user input, so no keydown listener is equivalent. Without the element, Enter did
    // nothing at all and the button was the only way in. The submit button being type=submit is
    // the other half; a <form> with no submit button has nothing for Enter to activate.
    // preventDefault stops the browser's own navigation, which here would reload the page and
    // throw away both the typed values and the Kotlin/JS runtime.
    Form(attrs = {
        classes("auth-fields")
        // `this.` is load-bearing: the composable's own `onSubmit` PARAMETER shadows the
        // attribute builder's `onSubmit`, so the bare name binds to the callback and silently
        // fails to compile as an event registration.
        this.onSubmit { event ->
            event.preventDefault()
            submit(email, password)
        }
    }) {
        Field(
            label = "Email",
            value = email,
            onInput = {
                email = it
                edited = true
            },
            leading = WebIcon.Mail,
            placeholder = "you@example.com",
            type = InputType.Email,
            error = badField == LoginField.EMAIL,
            id = EMAIL_ID,
            autocomplete = "username",
        )
        PasswordField(
            label = "Password",
            value = password,
            onInput = {
                password = it
                edited = true
            },
            error = badField == LoginField.PASSWORD,
            id = PASSWORD_ID,
            autocomplete = "current-password",
        )

        // Beside the field it is about, not buried in the footer with the account-creation
        // links: someone reaching for this has already failed to sign in, and the whole point is
        // that they find it without reading the page again.
        Div(attrs = { classes("auth-aside") }) {
            Span(attrs = {
                classes("lnk")
                onClick { onForgotPassword() }
            }) { Text("Forgot your password?") }
        }

        error?.let { Div(attrs = { classes("auth-err") }) { Text(it.userMessage()) } }

        Button(attrs = {
            classes("btn")
            attr("type", "submit")
            // No onClick: a submit button inside a form already submits it. Keeping one would
            // fire the handler twice for a click and once for Enter.
            if (state is LoginUiState.Loading) disabled()
        }) {
            Icon(WebIcon.LogIn, size = BUTTON_ICON_SIZE)
            Text(if (state is LoginUiState.Loading) "Signing in…" else "Sign in")
        }

        if (openRegistration) {
            Div(attrs = { classes("auth-alt") }) {
                Span { Text("New to ListenUp?") }
                Span(attrs = {
                    classes("lnk")
                    onClick { onRegister() }
                }) { Text("Create account") }
            }
        }
    }
}

/**
 * User-facing copy for a login failure.
 *
 * `InvalidCredentials` deliberately does not say which half was wrong — the shared ViewModel
 * already folds `AccountDenied` and `PendingApproval` into it, and distinguishing them here would
 * leak account existence to anyone guessing.
 */
private fun LoginErrorType.userMessage(): String =
    when (this) {
        is LoginErrorType.InvalidCredentials -> {
            "Email or password is incorrect."
        }

        is LoginErrorType.NetworkError -> {
            detail ?: "Could not reach the server. Check your connection."
        }

        is LoginErrorType.ServerError -> {
            detail ?: "The server had a problem. Try again shortly."
        }

        is LoginErrorType.ValidationError -> {
            when (field) {
                LoginField.EMAIL -> "Enter a valid email address."
                LoginField.PASSWORD -> "Enter your password."
            }
        }
    }

internal const val EMAIL_ID = "auth-email"

internal const val PASSWORD_ID = "auth-password"

private const val BUTTON_ICON_SIZE = 19
