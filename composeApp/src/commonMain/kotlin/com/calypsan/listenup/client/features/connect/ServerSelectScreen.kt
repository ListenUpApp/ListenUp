@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.features.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrandLogo
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.features.permission.RequestLocalNetworkPermission
import com.calypsan.listenup.client.presentation.connect.ServerSelectUiEvent
import com.calypsan.listenup.client.presentation.connect.ServerSelectUiState
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.connect_add_server_manually
import listenup.composeapp.generated.resources.connect_enter_server_url_directly
import listenup.composeapp.generated.resources.connect_make_sure_your_listenup_server
import listenup.composeapp.generated.resources.common_no_items_found
import listenup.composeapp.generated.resources.common_refresh
import listenup.composeapp.generated.resources.connect_select_server
import listenup.composeapp.generated.resources.common_selected

/**
 * Server selection screen showing discovered and saved servers.
 *
 * Features:
 * - Lists servers discovered via mDNS with online status
 * - Shows previously connected servers (may be offline)
 * - Option to add a server manually via URL
 * - Refresh button to restart discovery
 *
 * @param onServerActivated Callback when a server is selected and activated
 * @param onManualEntryRequested Callback when user wants to enter URL manually
 * @param modifier Modifier for the root composable
 */
@Composable
fun ServerSelectScreen(
    onServerActivated: () -> Unit,
    onManualEntryRequested: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerSelectViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Request ACCESS_LOCAL_NETWORK before starting mDNS discovery.
    // On Android 17+ the platform requires this permission for any multicast
    // traffic; on older Android and desktop the actual implementation grants
    // immediately without a dialog.
    RequestLocalNetworkPermission { granted ->
        if (granted) {
            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
        } else {
            viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionDenied)
        }
    }

    // Handle navigation events
    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                ServerSelectViewModel.NavigationEvent.ServerActivated -> onServerActivated()
                ServerSelectViewModel.NavigationEvent.GoToManualEntry -> onManualEntryRequested()
            }
        }
    }

    // Surface activation errors via snackbar; dismissing clears the VM overlay.
    LaunchedEffect(state) {
        val current = state
        if (current is ServerSelectUiState.Error) {
            snackbarHostState.showSnackbar(current.message)
            viewModel.onEvent(ServerSelectUiEvent.ErrorDismissed)
        }
    }

    ServerSelectContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

/**
 * Stateless content for ServerSelectScreen.
 */
@Composable
private fun ServerSelectContent(
    state: ServerSelectUiState,
    snackbarHostState: SnackbarHostState,
    onEvent: (ServerSelectUiEvent) -> Unit,
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
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Logo
            BrandLogo(size = 120.dp)

            Spacer(modifier = Modifier.height(32.dp))

            // Title with refresh button
            val isDiscovering = state is ServerSelectUiState.Discovering
            val connectingId = (state as? ServerSelectUiState.Connecting)?.selectedServerId
            val selectedId =
                when (state) {
                    is ServerSelectUiState.Connecting -> state.selectedServerId
                    is ServerSelectUiState.Error -> state.selectedServerId
                    else -> null
                }

            Row(
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.connect_select_server),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                IconButton(
                    onClick = { onEvent(ServerSelectUiEvent.RefreshClicked) },
                    enabled = !isDiscovering,
                ) {
                    if (isDiscovering) {
                        ListenUpLoadingIndicatorSmall()
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(Res.string.common_refresh),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server list
            LazyColumn(
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Discovered/saved servers
                items(
                    items = state.servers,
                    key = { it.server.id },
                ) { serverWithStatus ->
                    ServerCard(
                        serverWithStatus = serverWithStatus,
                        isSelected = selectedId == serverWithStatus.server.id,
                        isConnecting = connectingId == serverWithStatus.server.id,
                        onClick = { onEvent(ServerSelectUiEvent.ServerSelected(serverWithStatus)) },
                    )
                }

                // Empty state
                if (state.servers.isEmpty() && !isDiscovering) {
                    item {
                        EmptyState()
                    }
                }

                // Manual entry option
                item {
                    ManualEntryCard(
                        onClick = { onEvent(ServerSelectUiEvent.ManualEntryClicked) },
                    )
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * Card displaying a server with its online status.
 */
@Suppress("CognitiveComplexMethod")
@Composable
private fun ServerCard(
    serverWithStatus: ServerWithStatus,
    isSelected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val server = serverWithStatus.server
    val isOnline = serverWithStatus.isOnline

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = !isConnecting) { onClick() },
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Online indicator
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isOnline) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                ),
                    )
                    Text(
                        text = if (isOnline) "Online" else "Offline",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    // Server version
                    server.serverVersion.takeIf { it != "unknown" }?.let { version ->
                        Text(
                            text = "v$version",
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                        )
                    }
                }
            }

            // Loading or check indicator
            AnimatedVisibility(
                visible = isConnecting || isSelected,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                if (isConnecting) {
                    ListenUpLoadingIndicatorSmall()
                } else if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(Res.string.common_selected),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * Card for manual server entry option.
 */
@Composable
private fun ManualEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    text = stringResource(Res.string.connect_add_server_manually),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.connect_enter_server_url_directly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Empty state when no servers are discovered.
 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.common_no_items_found, "servers"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.connect_make_sure_your_listenup_server),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
