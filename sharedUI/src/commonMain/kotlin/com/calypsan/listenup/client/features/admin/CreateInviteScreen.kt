@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import com.calypsan.listenup.client.design.util.rememberCopyToClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.presentation.admin.CreateInviteErrorType
import com.calypsan.listenup.client.presentation.admin.CreateInviteField
import com.calypsan.listenup.client.presentation.admin.CreateInviteStatus
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_1_day
import listenup.composeapp.generated.resources.common_n_days

import listenup.composeapp.generated.resources.common_admin
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_copy
import listenup.composeapp.generated.resources.admin_create_an_invite_to_share
import listenup.composeapp.generated.resources.admin_create_another
import listenup.composeapp.generated.resources.admin_create_invite
import listenup.composeapp.generated.resources.common_done
import listenup.composeapp.generated.resources.admin_expires_in
import listenup.composeapp.generated.resources.admin_invite_created
import listenup.composeapp.generated.resources.common_member
import listenup.composeapp.generated.resources.common_role
import listenup.composeapp.generated.resources.admin_share_this_link_with_invitename

/**
 * Create invite screen - form to create new invites.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInviteScreen(
    viewModel: CreateInviteViewModel,
    onBackClick: () -> Unit,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberCopyToClipboard()

    // Handle success - show link and allow copy; surface non-validation errors in snackbar.
    val readyStatus = (state as? CreateInviteUiState.Ready)?.status
    LaunchedEffect(readyStatus) {
        when (readyStatus) {
            is CreateInviteStatus.Success -> {
                // Auto-copy the link
                copyToClipboard(readyStatus.invite.url)
                snackbarHostState.showSnackbar("Invite created! Link copied to clipboard.")
            }

            is CreateInviteStatus.Error -> {
                val message =
                    when (val type = readyStatus.type) {
                        is CreateInviteErrorType.ValidationError -> null
                        is CreateInviteErrorType.EmailInUse -> "A user with this email already exists"
                        is CreateInviteErrorType.NetworkError -> type.detail ?: "Network error"
                        is CreateInviteErrorType.ServerError -> type.detail ?: "Server error"
                    }
                message?.let {
                    snackbarHostState.showSnackbar(it)
                    viewModel.clearError()
                }
            }

            else -> {}
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.admin_create_invite)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val s = state) {
            is CreateInviteUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is CreateInviteUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is CreateInviteUiState.Ready -> {
                when (val status = s.status) {
                    is CreateInviteStatus.Success -> {
                        SuccessContent(
                            inviteUrl = status.invite.url,
                            inviteName = status.invite.name,
                            onCopyClick = {
                                scope.launch {
                                    copyToClipboard(status.invite.url)
                                    snackbarHostState.showSnackbar("Link copied!")
                                }
                            },
                            onCreateAnother = {
                                viewModel.reset()
                            },
                            onDone = onSuccess,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }

                    else -> {
                        CreateInviteForm(
                            status = status,
                            onSubmit = viewModel::createInvite,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun CreateInviteForm(
    status: CreateInviteStatus,
    onSubmit: (String, String, String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("member") }
    var expiresInDays by remember { mutableIntStateOf(7) }

    val focusManager = LocalFocusManager.current
    val isSubmitting = status is CreateInviteStatus.Submitting
    val validationField =
        (status as? CreateInviteStatus.Error)
            ?.type
            ?.let { it as? CreateInviteErrorType.ValidationError }
            ?.field

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.admin_create_an_invite_to_share),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ListenUpTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name",
            enabled = !isSubmitting,
            isError = validationField == CreateInviteField.NAME,
            supportingText =
                if (validationField == CreateInviteField.NAME) {
                    "Name is required"
                } else {
                    "The person's display name"
                },
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

        ListenUpTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            enabled = !isSubmitting,
            isError = validationField == CreateInviteField.EMAIL,
            supportingText =
                if (validationField == CreateInviteField.EMAIL) {
                    "Valid email is required"
                } else {
                    "Their email address for login"
                },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (!isSubmitting) {
                            onSubmit(name, email, role, expiresInDays)
                        }
                    },
                ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Role selection
        Column {
            Text(
                text = stringResource(Res.string.common_role),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = role == "member",
                    onClick = { role = "member" },
                    label = { Text(stringResource(Res.string.common_member)) },
                    enabled = !isSubmitting,
                )
                FilterChip(
                    selected = role == "admin",
                    onClick = { role = "admin" },
                    label = { Text(stringResource(Res.string.common_admin)) },
                    enabled = !isSubmitting,
                )
            }
            Text(
                text = if (role == "admin") "Can manage users and invites" else "Can access the library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // Expiration
        Column {
            Text(
                text = stringResource(Res.string.admin_expires_in),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = expiresInDays == 1,
                    onClick = { expiresInDays = 1 },
                    label = { Text(stringResource(Res.string.admin_1_day)) },
                    enabled = !isSubmitting,
                )
                FilterChip(
                    selected = expiresInDays == 7,
                    onClick = { expiresInDays = 7 },
                    label = { Text(stringResource(Res.string.common_n_days, "7")) },
                    enabled = !isSubmitting,
                )
                FilterChip(
                    selected = expiresInDays == 30,
                    onClick = { expiresInDays = 30 },
                    label = { Text(stringResource(Res.string.common_n_days, "30")) },
                    enabled = !isSubmitting,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ListenUpButton(
            onClick = { onSubmit(name, email, role, expiresInDays) },
            text = stringResource(Res.string.admin_create_invite),
            enabled = !isSubmitting,
            isLoading = isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SuccessContent(
    inviteUrl: String,
    inviteName: String,
    onCopyClick: () -> Unit,
    onCreateAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.admin_invite_created),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                Text(
                    text = stringResource(Res.string.admin_share_this_link_with_invitename, inviteName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = inviteUrl,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        IconButton(onClick = onCopyClick) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(Res.string.common_copy),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        ListenUpButton(
            onClick = onDone,
            text = stringResource(Res.string.common_done),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        androidx.compose.material3.TextButton(onClick = onCreateAnother) {
            Text(stringResource(Res.string.admin_create_another))
        }
    }
}
