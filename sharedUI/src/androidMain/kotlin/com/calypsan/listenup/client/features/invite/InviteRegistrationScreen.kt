package com.calypsan.listenup.client.features.invite

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.theme.LocalDarkTheme
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.presentation.invite.InviteErrorType
import com.calypsan.listenup.client.presentation.invite.InviteField
import com.calypsan.listenup.client.presentation.invite.InviteRegistrationUiState
import com.calypsan.listenup.client.presentation.invite.InviteRegistrationViewModel

@Composable
fun InviteRegistrationScreen(
    viewModel: InviteRegistrationViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        val current = state
        if (current is InviteRegistrationUiState.SubmitError) {
            val message =
                when (val type = current.errorType) {
                    is InviteErrorType.ValidationError -> null
                    is InviteErrorType.PasswordMismatch -> "Passwords don't match"
                    is InviteErrorType.NetworkError -> type.detail ?: "Network error"
                    is InviteErrorType.ServerError -> type.detail ?: "Server error"
                    is InviteErrorType.InviteInvalid -> "This invite is no longer valid"
                }
            message?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    when (val current = state) {
        is InviteRegistrationUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is InviteRegistrationUiState.Ready -> {
            InviteRegistrationContent(
                details = current.details,
                isSubmitting = false,
                errorType = null,
                onSubmit = viewModel::submitRegistration,
                onCancel = onCancel,
                snackbarHostState = snackbarHostState,
                modifier = modifier,
            )
        }

        is InviteRegistrationUiState.Submitting -> {
            InviteRegistrationContent(
                details = current.details,
                isSubmitting = true,
                errorType = null,
                onSubmit = viewModel::submitRegistration,
                onCancel = onCancel,
                snackbarHostState = snackbarHostState,
                modifier = modifier,
            )
        }

        is InviteRegistrationUiState.SubmitError -> {
            InviteRegistrationContent(
                details = current.details,
                isSubmitting = false,
                errorType = current.errorType,
                onSubmit = viewModel::submitRegistration,
                onCancel = onCancel,
                snackbarHostState = snackbarHostState,
                modifier = modifier,
            )
        }

        is InviteRegistrationUiState.Submitted -> {
            FullScreenLoadingIndicator()
        }

        is InviteRegistrationUiState.Invalid -> {
            InvalidInviteContent(
                message = current.reason,
                onDismiss = onCancel,
                modifier = modifier,
            )
        }

        is InviteRegistrationUiState.LoadError -> {
            ErrorContent(
                message = current.message,
                onRetry = viewModel::loadInviteDetails,
                onCancel = onCancel,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun InviteRegistrationContent(
    details: InviteDetails,
    isSubmitting: Boolean,
    errorType: InviteErrorType?,
    onSubmit: (String, String) -> Unit,
    onCancel: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
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
            Spacer(modifier = Modifier.height(48.dp))

            BrandLogo()

            Spacer(modifier = Modifier.height(32.dp))

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
                RegistrationForm(
                    details = details,
                    isSubmitting = isSubmitting,
                    errorType = errorType,
                    onSubmit = onSubmit,
                    onCancel = onCancel,
                    modifier = Modifier.padding(24.dp),
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun BrandLogo(modifier: Modifier = Modifier) {
    val isDarkTheme = LocalDarkTheme.current
    val logoRes =
        if (isDarkTheme) {
            R.drawable.listenup_logo_white
        } else {
            R.drawable.listenup_logo_black
        }

    Image(
        painter = painterResource(logoRes),
        contentDescription = "ListenUp Logo",
        modifier = modifier.size(120.dp),
    )
}

@Composable
private fun RegistrationForm(
    details: InviteDetails,
    isSubmitting: Boolean,
    errorType: InviteErrorType?,
    onSubmit: (String, String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val focusManager = LocalFocusManager.current
    val validationField =
        (errorType as? InviteErrorType.ValidationError)?.field
    val isPasswordMismatch = errorType is InviteErrorType.PasswordMismatch

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Welcome, ${details.name}!",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = "You've been invited to join an audiobook library. Set a password to complete your account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        InviteDetailsCard(details = details)

        Spacer(modifier = Modifier.height(8.dp))

        ListenUpTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            enabled = !isSubmitting,
            visualTransformation = PasswordVisualTransformation(),
            isError = validationField == InviteField.PASSWORD,
            supportingText =
                if (validationField == InviteField.PASSWORD) {
                    "Password must be at least 8 characters"
                } else {
                    "At least 8 characters"
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

        ListenUpTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = "Confirm Password",
            enabled = !isSubmitting,
            visualTransformation = PasswordVisualTransformation(),
            isError = isPasswordMismatch,
            supportingText = if (isPasswordMismatch) "Passwords don't match" else null,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (!isSubmitting) onSubmit(password, confirmPassword)
                    },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        ListenUpButton(
            onClick = { onSubmit(password, confirmPassword) },
            text = "Get Started",
            enabled = !isSubmitting,
            isLoading = isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = onCancel,
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun InviteDetailsCard(
    details: InviteDetails,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = "Server",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = details.serverName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = "Invited by",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = details.invitedBy,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Text(
                text = "Your email: ${details.email}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InvalidInviteContent(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandLogo()

            Spacer(modifier = Modifier.height(32.dp))

            ElevatedCard(
                modifier =
                    Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth(),
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Invite Invalid",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )

                    OutlinedButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandLogo()

            Spacer(modifier = Modifier.height(32.dp))

            ElevatedCard(
                modifier =
                    Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Connection Error",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                        ListenUpButton(
                            onClick = onRetry,
                            text = "Retry",
                        )
                    }
                }
            }
        }
    }
}
