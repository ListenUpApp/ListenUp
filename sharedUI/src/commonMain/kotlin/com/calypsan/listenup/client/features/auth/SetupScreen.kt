package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrandLogo
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.presentation.auth.SetupErrorType
import com.calypsan.listenup.client.presentation.auth.SetupField
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_confirm_password
import listenup.composeapp.generated.resources.auth_create_account
import listenup.composeapp.generated.resources.auth_create_admin_account
import listenup.composeapp.generated.resources.auth_first_name
import listenup.composeapp.generated.resources.auth_last_name
import listenup.composeapp.generated.resources.auth_set_up_your_listenup_server

/**
 * Initial server setup screen for creating the root/admin user.
 *
 * Features:
 * - Adaptive layout: side-by-side on wide screens, vertical on narrow
 * - Form for first name, last name, email, password, confirm password
 * - Client-side validation with field-specific errors
 * - Snackbar for network/server errors
 * - Auto-navigation on success via AuthState
 */
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar for non-validation errors
    LaunchedEffect(state) {
        val current = state
        if (current is SetupUiState.Error) {
            val message =
                when (current.type) {
                    is SetupErrorType.NetworkError -> "Network error. Please check your connection."
                    is SetupErrorType.ServerError -> "Server error. Please try again."
                    is SetupErrorType.AlreadyConfigured -> "Server is already configured."
                    is SetupErrorType.ValidationError -> null // Handled inline
                }
            message?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    SetupContent(
        state = state,
        onSubmit = viewModel::onSetupSubmit,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * Stateless content for SetupScreen.
 */
@Composable
private fun SetupContent(
    state: SetupUiState,
    onSubmit: (String, String, String, String, String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val useWideLayout =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (useWideLayout) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                // Left pane: logo
                Box(
                    modifier =
                        Modifier
                            .weight(0.4f)
                            .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    BrandLogo()
                }

                // Right pane: form
                Box(
                    modifier =
                        Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                            .imePadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 32.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SetupForm(
                            state = state,
                            onSubmit = onSubmit,
                            modifier = Modifier.widthIn(max = 480.dp),
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                BrandLogo()

                Spacer(modifier = Modifier.height(24.dp))

                ElevatedCard(
                    modifier =
                        Modifier
                            .widthIn(max = 480.dp)
                            .fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors =
                        CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                ) {
                    SetupForm(
                        state = state,
                        onSubmit = onSubmit,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Setup form content.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@Composable
private fun SetupForm(
    state: SetupUiState,
    onSubmit: (String, String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }

    val focusManager = LocalFocusManager.current
    val isLoading = state is SetupUiState.Loading
    val validationField =
        ((state as? SetupUiState.Error)?.type as? SetupErrorType.ValidationError)?.field

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Title
        Text(
            text = stringResource(Res.string.auth_create_admin_account),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = stringResource(Res.string.auth_set_up_your_listenup_server),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // First Name
        ListenUpTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = stringResource(Res.string.auth_first_name),
            enabled = !isLoading,
            isError = validationField == SetupField.FIRST_NAME,
            supportingText = if (validationField == SetupField.FIRST_NAME) "First name is required" else null,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Last Name
        ListenUpTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = stringResource(Res.string.auth_last_name),
            enabled = !isLoading,
            isError = validationField == SetupField.LAST_NAME,
            supportingText = if (validationField == SetupField.LAST_NAME) "Last name is required" else null,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Email
        ListenUpTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            enabled = !isLoading,
            isError = validationField == SetupField.EMAIL,
            supportingText = if (validationField == SetupField.EMAIL) "Invalid email address" else null,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Password
        ListenUpTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            enabled = !isLoading,
            visualTransformation = PasswordVisualTransformation(),
            isError = validationField == SetupField.PASSWORD,
            supportingText =
                if (validationField ==
                    SetupField.PASSWORD
                ) {
                    "Password must be at least 8 characters"
                } else {
                    null
                },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Confirm Password
        ListenUpTextField(
            value = passwordConfirm,
            onValueChange = { passwordConfirm = it },
            label = stringResource(Res.string.auth_confirm_password),
            enabled = !isLoading,
            visualTransformation = PasswordVisualTransformation(),
            isError = validationField == SetupField.PASSWORD_CONFIRM,
            supportingText = if (validationField == SetupField.PASSWORD_CONFIRM) "Passwords do not match" else null,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (!isLoading) {
                            onSubmit(firstName, lastName, email, password, passwordConfirm)
                        }
                    },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Submit button
        ListenUpButton(
            onClick = { onSubmit(firstName, lastName, email, password, passwordConfirm) },
            text = stringResource(Res.string.auth_create_account),
            enabled = !isLoading,
            isLoading = isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
