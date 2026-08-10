package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PasswordField
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Requests an account on a server with open registration. Pure in [state].
 *
 * On success the shared ViewModel moves `AuthState` to `PendingApproval`, so this screen has no
 * success branch of its own — the gate swaps it out.
 */
@Composable
fun RegisterForm(
    state: RegisterUiState,
    onSubmit: (email: String, password: String, firstName: String, lastName: String) -> Unit,
    onBack: () -> Unit,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Div(attrs = { classes("auth-fields") }) {
        Div(attrs = { classes("auth-row") }) {
            Field(label = "First name", value = firstName, onInput = { firstName = it }, id = FIRST_NAME_ID)
            Field(label = "Last name", value = lastName, onInput = { lastName = it }, id = LAST_NAME_ID)
        }
        Field(
            label = "Email",
            value = email,
            onInput = { email = it },
            leading = WebIcon.Mail,
            placeholder = "you@example.com",
            type = InputType.Email,
            id = EMAIL_ID,
        )
        PasswordField(label = "Password", value = password, onInput = { password = it }, id = PASSWORD_ID)

        // The shared state carries a raw String here rather than a semantic error type, unlike
        // LoginUiState and SetupUiState. Rendered verbatim on purpose: substituting our own copy
        // would hide what the server actually said, and normalising the shared type belongs in
        // its own change.
        (state as? RegisterUiState.Error)?.let {
            Div(attrs = { classes("auth-err") }) { Text(it.message) }
        }

        Button(attrs = {
            classes("btn")
            attr("type", "button")
            if (state is RegisterUiState.Loading) disabled()
            onClick { onSubmit(email, password, firstName, lastName) }
        }) {
            Icon(WebIcon.UserPlus, size = BUTTON_ICON_SIZE)
            Text(if (state is RegisterUiState.Loading) "Requesting…" else "Create account")
        }

        Div(attrs = { classes("auth-alt") }) {
            Span { Text("Already have an account?") }
            Span(attrs = {
                classes("lnk")
                onClick { onBack() }
            }) { Text("Sign in") }
        }
    }
}

private const val BUTTON_ICON_SIZE = 19
