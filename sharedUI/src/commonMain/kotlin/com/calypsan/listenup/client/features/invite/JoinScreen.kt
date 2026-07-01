package com.calypsan.listenup.client.features.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.features.auth.components.AuthBadge
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_your_email

/**
 * Public invite-claim landing screen — the redeem half of the invite vertical.
 *
 * Drives [ClaimInviteViewModel] through lookup → preview → claim. When the app
 * is opened via an invite deep link the [initialCode] is supplied and the screen
 * auto-submits it via [ClaimInviteViewModel.start], landing directly on the
 * [ClaimInviteUiState.Preview] step. The deep link also carries its own [serverUrl];
 * the ViewModel persists it before the lookup so the RPC factory can resolve a base
 * URL even on a fresh install. With no code (manual entry) the screen opens on a
 * code-entry field.
 *
 * A successful claim lands the user logged-in (the repository persists the issued
 * session); the screen signals that via [onClaimed] so the host can dismiss the
 * deep link / route to the authenticated shell.
 *
 * @param onClaimed Invoked once when the claim succeeds.
 * @param onCancel Invoked when the user backs out of the flow.
 * @param initialCode Optional deep-link code; auto-submitted for lookup on first composition.
 * @param serverUrl Optional deep-link server URL; persisted before lookup so the
 *   anonymous RPC surface has a base URL to connect to on a fresh install.
 */
@Composable
fun JoinScreen(
    onClaimed: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialCode: String? = null,
    serverUrl: String? = null,
    remoteUrl: String? = null,
    viewModel: ClaimInviteViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Deep-link entry contract: when a code arrives with the link, hand it to the
    // ViewModel's start() so a reachable server URL is persisted before the lookup fires,
    // landing the user on Preview rather than an empty field. The link's optional remote
    // URL rides along so an off-LAN invitee still connects (local-first, remote fallback).
    LaunchedEffect(initialCode, serverUrl, remoteUrl) {
        if (!initialCode.isNullOrBlank()) {
            viewModel.start(serverUrl = serverUrl, code = initialCode, remoteUrl = remoteUrl)
        }
    }

    LaunchedEffect(state) {
        if (state is ClaimInviteUiState.Claimed) {
            onClaimed()
        }
    }

    when (val current = state) {
        ClaimInviteUiState.Idle -> {
            CodeEntryStep(onCodeEntered = viewModel::onCodeEntered, onCancel = onCancel, modifier = modifier)
        }

        ClaimInviteUiState.LookingUp -> {
            FullScreenLoadingIndicator()
        }

        is ClaimInviteUiState.Preview -> {
            ClaimStep(
                preview = current.preview,
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
            ErrorStep(message = current.message, onCancel = onCancel, modifier = modifier)
        }
    }
}

@Composable
private fun CodeEntryStep(
    onCodeEntered: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AuthScaffold(
        title = "Join a library",
        subtitle = "Enter your invite code to get started.",
        onBack = onCancel,
        modifier = modifier,
    ) {
        ListenUpTextField(
            value = code,
            onValueChange = { code = it },
            label = "Invite code",
            leadingIcon = Icons.Outlined.Badge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (code.isNotBlank()) onCodeEntered(code.trim())
                    },
                ),
        )
        ListenUpButton(
            onClick = { onCodeEntered(code.trim()) },
            text = "Continue",
            enabled = code.isNotBlank(),
        )
    }
}

@Composable
private fun ClaimStep(
    preview: InvitePreview,
    onClaim: (password: String, firstName: String, lastName: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!preview.valid) {
        ErrorStep(
            message =
                preview.invalidReason
                    ?: "This invite is no longer valid. It may have already been used or expired.",
            onCancel = onCancel,
            modifier = modifier,
        )
        return
    }

    var password by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AuthScaffold(
        title = "You're invited",
        subtitle = "${preview.invitedByName} invited you to ${preview.serverName}. Set a password to finish.",
        badge = AuthBadge(icon = Icons.Outlined.Storage, label = preview.serverName),
        onBack = onCancel,
        modifier = modifier,
    ) {
        InvitePreviewCard(preview)

        ListenUpTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = "First name",
            leadingIcon = Icons.Outlined.Person,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        )

        ListenUpTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = "Last name",
            leadingIcon = Icons.Outlined.Person,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
        )

        ListenUpTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            supportingText = "At least 8 characters",
            leadingIcon = Icons.Outlined.Lock,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onClaim(password, firstName, lastName)
                    },
                ),
        )

        ListenUpButton(
            onClick = { onClaim(password, firstName, lastName) },
            text = "Get started",
            leadingIcon = Icons.AutoMirrored.Outlined.Login,
            enabled = firstName.isNotBlank() && lastName.isNotBlank() && password.isNotBlank(),
        )
    }
}

@Composable
private fun InvitePreviewCard(
    preview: InvitePreview,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewRow(label = "Server", value = preview.serverName, icon = Icons.Outlined.Storage)
            PreviewRow(label = "Invited by", value = preview.invitedByName, icon = Icons.Outlined.Person)
            Text(
                text = stringResource(Res.string.auth_your_email, preview.email),
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
private fun ErrorStep(
    message: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AuthScaffold(
            title = "Invite problem",
            subtitle = message,
            onBack = onCancel,
        ) {
            ListenUpButton(onClick = onCancel, text = "Dismiss")
        }
    }
}
