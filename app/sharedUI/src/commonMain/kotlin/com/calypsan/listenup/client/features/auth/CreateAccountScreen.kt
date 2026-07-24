package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.features.auth.components.AuthBadge
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.client.presentation.auth.RegisterViewModel
import com.calypsan.listenup.client.presentation.auth.SetupErrorType
import com.calypsan.listenup.client.presentation.auth.SetupField
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.client.presentation.auth.SetupViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_already_have_account
import listenup.composeapp.generated.resources.auth_confirm_password
import listenup.composeapp.generated.resources.auth_create_account
import listenup.composeapp.generated.resources.auth_create_admin_account
import listenup.composeapp.generated.resources.auth_create_an_account_request_an
import listenup.composeapp.generated.resources.auth_first_name
import listenup.composeapp.generated.resources.auth_last_name
import listenup.composeapp.generated.resources.auth_passwords_dont_match
import listenup.composeapp.generated.resources.auth_request_account
import listenup.composeapp.generated.resources.auth_server_administrator
import listenup.composeapp.generated.resources.auth_set_up_your_listenup_server
import listenup.composeapp.generated.resources.auth_sign_in

/**
 * The two faces of "create an account" share one form — name, email, password, confirm — and differ
 * only in framing and destination:
 * - [CreateAccountMode.Setup] creates the server's first admin (no way back; success authenticates).
 * - [CreateAccountMode.Register] requests a normal account on a server with open registration
 *   (backs out to sign-in; success lands on pending-approval).
 *
 * Both render through the shared [AuthScaffold] hero/split chrome. [SetupScreen] and [RegisterScreen]
 * own their respective ViewModels and feed the same stateless [CreateAccountScreen].
 */
private enum class CreateAccountMode { Setup, Register }

/**
 * Initial server setup — creates the root admin account.
 *
 * Validation runs client-side in [SetupViewModel], so field-specific [SetupField] errors highlight
 * the right input; network/server failures surface via the snackbar. Success flips `AuthState`,
 * and navigation routes onward without screen-side handling.
 */
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        val current = state
        if (current is SetupUiState.Error) {
            val message =
                when (current.type) {
                    is SetupErrorType.NetworkError -> "Network error. Please check your connection."
                    is SetupErrorType.ServerError -> "Server error. Please try again."
                    is SetupErrorType.AlreadyConfigured -> "Server is already configured."
                    is SetupErrorType.ValidationError -> null // Handled inline.
                }
            message?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    val validationField =
        ((state as? SetupUiState.Error)?.type as? SetupErrorType.ValidationError)?.field

    CreateAccountScreen(
        mode = CreateAccountMode.Setup,
        isLoading = state is SetupUiState.Loading,
        validationField = validationField,
        onSubmit = viewModel::onSetupSubmit,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * Open-registration sign-up — requests an account pending admin approval.
 *
 * Only reached when the server enables open registration. Success transitions `AuthState` to
 * PendingApproval; the back affordance pops to sign-in.
 *
 * @param onBackClick Pops back to the sign-in screen.
 */
@Composable
fun RegisterScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        val current = state
        if (current is RegisterUiState.Error) {
            snackbarHostState.showSnackbar(current.message)
            viewModel.clearError()
        }
    }

    CreateAccountScreen(
        mode = CreateAccountMode.Register,
        isLoading = state is RegisterUiState.Loading,
        validationField = null,
        onSubmit = { firstName, lastName, email, password, _ ->
            viewModel.onRegisterSubmit(email, password, firstName, lastName)
        },
        onBack = onBackClick,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
private fun CreateAccountScreen(
    mode: CreateAccountMode,
    isLoading: Boolean,
    validationField: SetupField?,
    onSubmit: (firstName: String, lastName: String, email: String, password: String, confirm: String) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val title =
        when (mode) {
            CreateAccountMode.Setup -> Res.string.auth_create_admin_account
            CreateAccountMode.Register -> Res.string.auth_request_account
        }
    val subtitle =
        when (mode) {
            CreateAccountMode.Setup -> Res.string.auth_set_up_your_listenup_server
            CreateAccountMode.Register -> Res.string.auth_create_an_account_request_an
        }
    val badge =
        if (mode == CreateAccountMode.Setup) {
            AuthBadge(
                icon = Icons.Outlined.AdminPanelSettings,
                label = stringResource(Res.string.auth_server_administrator),
            )
        } else {
            null
        }

    Box(modifier = modifier.fillMaxSize()) {
        AuthScaffold(
            title = stringResource(title),
            subtitle = stringResource(subtitle),
            badge = badge,
            onBack = onBack,
        ) {
            CreateAccountFields(
                isLoading = isLoading,
                validationField = validationField,
                submitLabel = stringResource(Res.string.auth_create_account),
                requirePasswordMatch = mode == CreateAccountMode.Register,
                onSubmit = onSubmit,
            )
            if (mode == CreateAccountMode.Register && onBack != null) {
                SignInPrompt(onSignIn = onBack)
            }
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

@Suppress("LongMethod")
@Composable
internal fun CreateAccountFields(
    isLoading: Boolean,
    validationField: SetupField?,
    submitLabel: String,
    requirePasswordMatch: Boolean,
    onSubmit: (firstName: String, lastName: String, email: String, password: String, confirm: String) -> Unit,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val confirmMismatch = confirm.isNotEmpty() && password != confirm
    val canSubmit = !isLoading && (!requirePasswordMatch || (password.isNotEmpty() && password == confirm))

    fun submit() {
        focusManager.clearFocus()
        if (canSubmit) onSubmit(firstName, lastName, email, password, confirm)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListenUpTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = stringResource(Res.string.auth_first_name),
            enabled = !isLoading,
            isError = validationField == SetupField.FIRST_NAME,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            modifier = Modifier.weight(1f),
        )
        ListenUpTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = stringResource(Res.string.auth_last_name),
            enabled = !isLoading,
            isError = validationField == SetupField.LAST_NAME,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            modifier = Modifier.weight(1f),
        )
    }

    ListenUpTextField(
        value = email,
        onValueChange = { email = it },
        label = "Email",
        enabled = !isLoading,
        isError = validationField == SetupField.EMAIL,
        leadingIcon = Icons.Outlined.MailOutline,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
    )

    ListenUpTextField(
        value = password,
        onValueChange = { password = it },
        label = "Password",
        enabled = !isLoading,
        isError = validationField == SetupField.PASSWORD,
        leadingIcon = Icons.Outlined.Lock,
        trailingIcon = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
        onTrailingClick = { passwordVisible = !passwordVisible },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
    )

    ListenUpTextField(
        value = confirm,
        onValueChange = { confirm = it },
        label = stringResource(Res.string.auth_confirm_password),
        enabled = !isLoading,
        isError = validationField == SetupField.PASSWORD_CONFIRM || confirmMismatch,
        supportingText = if (confirmMismatch) stringResource(Res.string.auth_passwords_dont_match) else null,
        leadingIcon = Icons.Outlined.Lock,
        trailingIcon = if (confirmVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
        onTrailingClick = { confirmVisible = !confirmVisible },
        visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )

    ListenUpButton(
        onClick = { submit() },
        text = submitLabel,
        leadingIcon = Icons.Outlined.PersonAdd,
        enabled = canSubmit,
        isLoading = isLoading,
    )
}

@Composable
private fun SignInPrompt(onSignIn: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.auth_already_have_account),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onSignIn) {
            Text(stringResource(Res.string.auth_sign_in))
        }
    }
}
