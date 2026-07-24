package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.presentation.auth.LoginErrorType
import com.calypsan.listenup.client.presentation.auth.LoginField
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_change_server
import listenup.composeapp.generated.resources.auth_create_account
import listenup.composeapp.generated.resources.auth_new_to_listenup
import listenup.composeapp.generated.resources.auth_sign_in
import listenup.composeapp.generated.resources.auth_sign_in_to_access_your

/**
 * Sign-in screen — the entry point when a server is configured but the app holds no valid session.
 *
 * Renders through the shared [AuthScaffold]. Field validation highlights the offending input;
 * credential/network/server failures surface via the snackbar. Success flips `AuthState` and
 * navigation proceeds without screen-side routing.
 *
 * @param openRegistration Whether the "Create Account" link is shown.
 * @param onChangeServer Disconnects and returns to server selection.
 * @param onRegister Opens the account-request screen (only when [openRegistration]).
 */
@Composable
fun LoginScreen(
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    openRegistration: Boolean = false,
    onRegister: () -> Unit = {},
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        val current = state
        if (current is LoginUiState.Error) {
            val message =
                when (val type = current.type) {
                    is LoginErrorType.InvalidCredentials -> "Invalid email or password."
                    is LoginErrorType.NetworkError -> type.detail ?: "Network error. Check your connection."
                    is LoginErrorType.ServerError -> type.detail ?: "Server error. Please try again."
                    is LoginErrorType.ValidationError -> null // Handled inline.
                }
            message?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AuthScaffold(
            title = stringResource(Res.string.auth_sign_in),
            subtitle = stringResource(Res.string.auth_sign_in_to_access_your),
        ) {
            LoginFields(
                state = state,
                onSubmit = viewModel::onLoginSubmit,
            )
            LoginFooter(
                openRegistration = openRegistration,
                onRegister = onRegister,
                onChangeServer = onChangeServer,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(16.dp),
        )
    }
}

@Composable
internal fun LoginFields(
    state: LoginUiState,
    onSubmit: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val isLoading = state is LoginUiState.Loading
    val validationField = ((state as? LoginUiState.Error)?.type as? LoginErrorType.ValidationError)?.field

    fun submit() {
        focusManager.clearFocus()
        if (!isLoading) onSubmit(email, password)
    }

    ListenUpTextField(
        value = email,
        onValueChange = { email = it },
        label = "Email",
        enabled = !isLoading,
        isError = validationField == LoginField.EMAIL,
        leadingIcon = Icons.Outlined.MailOutline,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
    )

    ListenUpTextField(
        value = password,
        onValueChange = { password = it },
        label = "Password",
        enabled = !isLoading,
        isError = validationField == LoginField.PASSWORD,
        leadingIcon = Icons.Outlined.Lock,
        trailingIcon = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
        onTrailingClick = { passwordVisible = !passwordVisible },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )

    ListenUpButton(
        onClick = { submit() },
        text = stringResource(Res.string.auth_sign_in),
        leadingIcon = Icons.AutoMirrored.Outlined.Login,
        enabled = !isLoading,
        isLoading = isLoading,
    )
}

@Composable
internal fun LoginFooter(
    openRegistration: Boolean,
    onRegister: () -> Unit,
    onChangeServer: () -> Unit,
) {
    if (openRegistration) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.auth_new_to_listenup),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRegister) {
                Text(stringResource(Res.string.auth_create_account))
            }
        }
    }

    FilledTonalButton(
        onClick = onChangeServer,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(Icons.Outlined.Dns, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.auth_change_server))
    }
}
