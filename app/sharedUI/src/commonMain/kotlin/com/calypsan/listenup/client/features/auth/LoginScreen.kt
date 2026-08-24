package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.presentation.auth.LoginErrorType
import com.calypsan.listenup.client.presentation.auth.LoginField
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.LoginViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_change_server
import listenup.composeapp.generated.resources.auth_create_account
import listenup.composeapp.generated.resources.auth_forgot_password
import listenup.composeapp.generated.resources.auth_forgot_password_complete
import listenup.composeapp.generated.resources.auth_new_to_listenup
import listenup.composeapp.generated.resources.auth_password_label
import listenup.composeapp.generated.resources.auth_reset_root
import listenup.composeapp.generated.resources.auth_reset_root_explainer
import listenup.composeapp.generated.resources.auth_reset_root_token_label
import listenup.composeapp.generated.resources.auth_sign_in
import listenup.composeapp.generated.resources.auth_sign_in_to_access_your
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_continue
import listenup.composeapp.generated.resources.common_done

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
 * @param onForgotPassword Opens the admin-approval password-reset flow.
 */
@Composable
fun LoginScreen(
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    openRegistration: Boolean = false,
    onRegister: () -> Unit = {},
    // No default: a call site that forgets this ships a visible button that silently does
    // nothing (androidMain's LoginNavigation did exactly that). Every host must decide.
    onForgotPassword: () -> Unit,
    // Hidden by default — the root escape hatch surfaces only while the server reports
    // `LISTENUP_ROOT_RESET` armed ([ServerInfo.rootResetArmed]), so the once-in-a-blue-moon
    // operator affordance never clutters the everyday sign-in screen.
    showRootResetEntry: Boolean = false,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRootReset by remember { mutableStateOf(false) }

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
                onForgotPassword = onForgotPassword,
            )
            LoginFooter(
                openRegistration = openRegistration,
                onRegister = onRegister,
                onChangeServer = onChangeServer,
                showRootReset = showRootResetEntry,
                onResetRoot = { showRootReset = true },
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

    if (showRootReset) {
        RootResetDialog(onDismissRequest = { showRootReset = false })
    }
}

@Composable
internal fun LoginFields(
    state: LoginUiState,
    onSubmit: (String, String) -> Unit,
    onForgotPassword: () -> Unit = {},
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

    val haptics = LocalHaptics.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = {
                haptics.press()
                onForgotPassword()
            },
        ) {
            Text(stringResource(Res.string.auth_forgot_password))
        }
    }

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
    showRootReset: Boolean,
    onResetRoot: () -> Unit = {},
) {
    val haptics = LocalHaptics.current
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
            TextButton(
                onClick = {
                    haptics.press()
                    onRegister()
                },
            ) {
                Text(stringResource(Res.string.auth_create_account))
            }
        }
    }

    FilledTonalButton(
        onClick = {
            haptics.press()
            onChangeServer()
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(Icons.Outlined.Dns, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.auth_change_server))
    }

    // Only while the server reports the LISTENUP_ROOT_RESET hatch armed: the operator who just
    // armed it sees the entry for their 15-minute window; everyone else never sees it at all.
    if (showRootReset) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(
                onClick = {
                    haptics.press()
                    onResetRoot()
                },
            ) {
                Text(
                    text = stringResource(Res.string.auth_reset_root),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The root escape hatch: `LISTENUP_ROOT_RESET` arms a one-time token the server prints to its
 * log. Root has no admin above them to convey a code out of band, so this collects the token
 * directly instead of routing through the admin-approval flow [ForgotPasswordScreen] uses.
 *
 * Deliberately self-contained rather than backed by a dedicated ViewModel: it is a single
 * fire-and-forget mutation with no state to survive process death, so a locally `remember`ed
 * submission state is sufficient — mirroring how [com.calypsan.listenup.client.navigation
 * .LoginNavigation] already calls `ServerConfig.disconnectFromServer()` directly from a
 * composable rather than through a ViewModel.
 */
@Composable
private fun RootResetDialog(
    onDismissRequest: () -> Unit,
    repository: PasswordResetRepository = koinInject(),
) {
    var token by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var succeeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current

    fun submit() {
        isSubmitting = true
        scope.launch {
            when (val result = repository.resetRootPassword(token.trim(), newPassword)) {
                is AppResult.Success -> succeeded = true
                is AppResult.Failure -> errorMessage = result.error.message
            }
            isSubmitting = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.auth_reset_root)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (succeeded) {
                    Text(
                        text = stringResource(Res.string.auth_forgot_password_complete),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.auth_reset_root_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ListenUpTextField(
                        value = token,
                        onValueChange = {
                            token = it
                            errorMessage = null
                        },
                        label = stringResource(Res.string.auth_reset_root_token_label),
                        enabled = !isSubmitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    )
                    ListenUpTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            errorMessage = null
                        },
                        label = stringResource(Res.string.auth_password_label),
                        enabled = !isSubmitting,
                        isError = errorMessage != null,
                        supportingText = errorMessage,
                        trailingIcon = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        onTrailingClick = { passwordVisible = !passwordVisible },
                        visualTransformation =
                            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
            }
        },
        confirmButton = {
            if (succeeded) {
                TextButton(
                    onClick = {
                        haptics.press()
                        onDismissRequest()
                    },
                ) {
                    Text(stringResource(Res.string.common_done))
                }
            } else {
                TextButton(
                    enabled = !isSubmitting && token.isNotBlank() && newPassword.isNotBlank(),
                    onClick = { submit() },
                ) {
                    Text(stringResource(Res.string.common_continue))
                }
            }
        },
        dismissButton = {
            if (!succeeded) {
                TextButton(
                    onClick = {
                        haptics.press()
                        onDismissRequest()
                    },
                    enabled = !isSubmitting,
                ) {
                    Text(stringResource(Res.string.common_cancel))
                }
            }
        },
    )
}
