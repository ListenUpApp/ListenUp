package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.auth.SetupErrorType
import com.calypsan.listenup.client.presentation.auth.SetupField
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PasswordField
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * Creates the first admin on a server that has no users yet. Pure in [state].
 *
 * The confirm field is not a formality: `SetupViewModel.onSetupSubmit` compares the two locally
 * and refuses before any RPC, so a mismatch never reaches the network.
 */
@Composable
fun SetupForm(
    state: SetupUiState,
    onSubmit: (firstName: String, lastName: String, email: String, password: String, passwordConfirm: String) -> Unit,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val error = (state as? SetupUiState.Error)?.type
    val badField = (error as? SetupErrorType.ValidationError)?.field

    Div(attrs = { classes("auth-fields") }) {
        Div(attrs = { classes("auth-row") }) {
            Field(
                label = "First name",
                value = firstName,
                onInput = { firstName = it },
                error = badField == SetupField.FIRST_NAME,
                id = FIRST_NAME_ID,
            )
            Field(
                label = "Last name",
                value = lastName,
                onInput = { lastName = it },
                error = badField == SetupField.LAST_NAME,
                id = LAST_NAME_ID,
            )
        }
        Field(
            label = "Email",
            value = email,
            onInput = { email = it },
            leading = WebIcon.Mail,
            placeholder = "you@example.com",
            type = InputType.Email,
            error = badField == SetupField.EMAIL,
            id = EMAIL_ID,
        )
        Div(attrs = { classes("auth-row") }) {
            PasswordField(
                label = "Password",
                value = password,
                onInput = { password = it },
                error = badField == SetupField.PASSWORD,
                id = PASSWORD_ID,
            )
            PasswordField(
                label = "Confirm",
                value = confirm,
                onInput = { confirm = it },
                error = badField == SetupField.PASSWORD_CONFIRM,
                id = CONFIRM_ID,
            )
        }

        error?.let { Div(attrs = { classes("auth-err") }) { Text(it.userMessage()) } }

        Button(attrs = {
            classes("btn")
            attr("type", "button")
            if (state is SetupUiState.Loading) disabled()
            onClick { onSubmit(firstName, lastName, email, password, confirm) }
        }) {
            Icon(WebIcon.UserPlus, size = BUTTON_ICON_SIZE)
            Text(if (state is SetupUiState.Loading) "Creating…" else "Create admin account")
        }
    }
}

private fun SetupErrorType.userMessage(): String =
    when (this) {
        is SetupErrorType.NetworkError -> "Could not reach the server. Check your connection."
        is SetupErrorType.ServerError -> "The server had a problem. Try again shortly."
        is SetupErrorType.AlreadyConfigured -> "This server is already set up. Sign in instead."
        is SetupErrorType.ValidationError ->
            when (field) {
                SetupField.FIRST_NAME -> "Enter a first name."
                SetupField.LAST_NAME -> "Enter a last name."
                SetupField.EMAIL -> "Enter a valid email address."
                SetupField.PASSWORD -> "Choose a password."
                SetupField.PASSWORD_CONFIRM -> "The two passwords do not match."
            }
    }

internal const val FIRST_NAME_ID = "auth-first"

internal const val LAST_NAME_ID = "auth-last"

internal const val CONFIRM_ID = "auth-confirm"

private const val BUTTON_ICON_SIZE = 19
