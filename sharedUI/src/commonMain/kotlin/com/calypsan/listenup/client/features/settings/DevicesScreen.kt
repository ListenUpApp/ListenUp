package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.presentation.settings.DeviceRow
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.presentation.settings.DevicesViewModel
import com.calypsan.listenup.client.util.formatDateShort
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.devices_empty
import listenup.composeapp.generated.resources.devices_last_active
import listenup.composeapp.generated.resources.devices_sign_out
import listenup.composeapp.generated.resources.devices_sign_out_everywhere
import listenup.composeapp.generated.resources.devices_sign_out_everywhere_confirm
import listenup.composeapp.generated.resources.devices_this_device
import listenup.composeapp.generated.resources.devices_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Lists the caller's active sessions ("devices") and lets them revoke a single
 * device or sign out everywhere.
 *
 * The [DevicesViewModel] owns the authoritative session list; revoking a row
 * re-fetches rather than mutating optimistically, so the UI is a pure render
 * of [DevicesUiState].
 *
 * @param onBack Navigate back to Settings.
 * @param onSignedOutEverywhere Invoked after a global sign-out completes (e.g. route to login).
 * @param viewModel The Devices ViewModel, provided via Koin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onSignedOutEverywhere: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSignOutEverywhereDialog by remember { mutableStateOf(false) }

    if (showSignOutEverywhereDialog) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showSignOutEverywhereDialog = false },
            title = stringResource(Res.string.devices_sign_out_everywhere),
            text = stringResource(Res.string.devices_sign_out_everywhere_confirm),
            confirmText = stringResource(Res.string.devices_sign_out_everywhere),
            onConfirm = {
                showSignOutEverywhereDialog = false
                viewModel.signOutEverywhere(onSignedOutEverywhere)
            },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        DevicesBody(
            state = state,
            innerPadding = innerPadding,
            onRevokeDevice = viewModel::revokeDevice,
            onSignOutEverywhere = { showSignOutEverywhereDialog = true },
            onRetry = viewModel::retry,
        )
    }
}

@Composable
private fun DevicesBody(
    state: DevicesUiState,
    innerPadding: PaddingValues,
    onRevokeDevice: (String) -> Unit,
    onSignOutEverywhere: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is DevicesUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is DevicesUiState.Error -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry) {
                        Text(stringResource(Res.string.common_retry))
                    }
                }
            }
        }

        is DevicesUiState.Ready -> {
            DevicesContent(
                state = state,
                onRevokeDevice = onRevokeDevice,
                onSignOutEverywhere = onSignOutEverywhere,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun DevicesContent(
    state: DevicesUiState.Ready,
    onRevokeDevice: (String) -> Unit,
    onSignOutEverywhere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
    ) {
        if (state.devices.none { !it.isCurrent }) {
            item {
                Text(
                    text = stringResource(Res.string.devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        items(
            items = state.devices,
            key = { device -> device.sessionId },
        ) { device ->
            DeviceListItem(
                device = device,
                isSigningOut = device.sessionId in state.signingOut,
                onRevoke = { onRevokeDevice(device.sessionId) },
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onSignOutEverywhere,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                Text(stringResource(Res.string.devices_sign_out_everywhere))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceListItem(
    device: DeviceRow,
    isSigningOut: Boolean,
    onRevoke: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(device.displayName) },
        supportingContent = {
            Column {
                if (device.secondary.isNotBlank()) {
                    Text(
                        text = device.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(Res.string.devices_last_active, formatDateShort(device.lastUsedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            when {
                device.isCurrent -> {
                    Text(
                        text = stringResource(Res.string.devices_this_device),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                isSigningOut -> {
                    ListenUpLoadingIndicatorSmall()
                }

                else -> {
                    TextButton(onClick = onRevoke) {
                        Text(
                            text = stringResource(Res.string.devices_sign_out),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
