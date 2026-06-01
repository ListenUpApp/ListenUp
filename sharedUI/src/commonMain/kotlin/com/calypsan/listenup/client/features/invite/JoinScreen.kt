package com.calypsan.listenup.client.features.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.client.design.components.BrandLogo
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Public invite-claim landing screen — the redeem half of the invite vertical.
 *
 * Drives [ClaimInviteViewModel] through lookup → preview → claim. When the app
 * is opened via an invite deep link the [initialCode] is supplied and the screen
 * auto-submits it, landing directly on the [ClaimInviteUiState.Preview] step. With
 * no code (manual entry) the screen opens on a code-entry field.
 *
 * A successful claim lands the user logged-in (the repository persists the issued
 * session); the screen signals that via [onClaimed] so the host can dismiss the
 * deep link / route to the authenticated shell.
 *
 * @param onClaimed Invoked once when the claim succeeds.
 * @param onCancel Invoked when the user backs out of the flow.
 * @param initialCode Optional deep-link code; auto-submitted for lookup on first composition.
 */
@Composable
fun JoinScreen(
    onClaimed: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialCode: String? = null,
    viewModel: ClaimInviteViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Deep-link entry contract: when a code arrives with the link, submit it
    // immediately so the user lands on the Preview step rather than an empty field.
    LaunchedEffect(initialCode) {
        if (!initialCode.isNullOrBlank()) {
            viewModel.onCodeEntered(initialCode)
        }
    }

    LaunchedEffect(state) {
        if (state is ClaimInviteUiState.Claimed) {
            onClaimed()
        }
    }

    when (val current = state) {
        ClaimInviteUiState.Idle -> {
            CodeEntryContent(
                onCodeEntered = viewModel::onCodeEntered,
                onCancel = onCancel,
                modifier = modifier,
            )
        }

        ClaimInviteUiState.LookingUp -> {
            FullScreenLoadingIndicator()
        }

        is ClaimInviteUiState.Preview -> {
            ClaimFormContent(
                preview = current.preview,
                isSubmitting = false,
                onClaim = viewModel::onClaimSubmit,
                onCancel = onCancel,
                modifier = modifier,
            )
        }

        ClaimInviteUiState.Submitting -> {
            FullScreenLoadingIndicator()
        }

        ClaimInviteUiState.Claimed -> {
            FullScreenLoadingIndicator()
        }

        is ClaimInviteUiState.Error -> {
            ErrorContent(
                message = current.message,
                onCancel = onCancel,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun CodeEntryContent(
    onCodeEntered: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    CenteredCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Join a library",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Enter your invite code to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ListenUpTextField(
                value = code,
                onValueChange = { code = it },
                label = "Invite code",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (code.isNotBlank()) onCodeEntered(code.trim())
                        },
                    ),
                modifier = Modifier.fillMaxWidth(),
            )

            ListenUpButton(
                onClick = { onCodeEntered(code.trim()) },
                text = "Continue",
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ClaimFormContent(
    preview: InvitePreview,
    isSubmitting: Boolean,
    onClaim: (password: String, displayName: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!preview.valid) {
        ErrorContent(
            message =
                preview.invalidReason
                    ?: "This invite is no longer valid. It may have already been used or expired.",
            onCancel = onCancel,
            modifier = modifier,
        )
        return
    }

    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf(preview.displayName) }
    val focusManager = LocalFocusManager.current

    CenteredCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "${preview.invitedByName} invited you to ${preview.serverName}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Set a password to finish creating your account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            InvitePreviewCard(preview)

            ListenUpTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Display name",
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions =
                    KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )

            ListenUpTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                enabled = !isSubmitting,
                supportingText = "At least 8 characters",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (!isSubmitting) onClaim(password, displayName.ifBlank { null })
                        },
                    ),
                modifier = Modifier.fillMaxWidth(),
            )

            ListenUpButton(
                onClick = { onClaim(password, displayName.ifBlank { null }) },
                text = "Get started",
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
}

@Composable
private fun InvitePreviewCard(
    preview: InvitePreview,
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
            PreviewRow(
                label = "Server",
                value = preview.serverName,
                icon = {
                    Icon(
                        Icons.Outlined.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            PreviewRow(
                label = "Invited by",
                value = preview.invitedByName,
                icon = {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
            Text(
                text = "Your email: ${preview.email}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewRow(
    label: String,
    value: String,
    icon: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Invite Problem",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onCancel) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Shared scaffold for the join flow: centered logo above a width-capped card.
 * Mirrors the pre-login screen shape (logo + ElevatedCard form).
 */
@Composable
private fun CenteredCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) },
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
                colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
            ) {
                content()
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
