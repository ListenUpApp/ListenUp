package com.calypsan.listenup.client.features.connect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.features.permission.RequestLocalNetworkPermission
import com.calypsan.listenup.client.presentation.connect.ServerSelectUiEvent
import com.calypsan.listenup.client.presentation.connect.ServerSelectUiState
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_no_items_found
import listenup.composeapp.generated.resources.common_offline
import listenup.composeapp.generated.resources.common_online
import listenup.composeapp.generated.resources.common_refresh
import listenup.composeapp.generated.resources.common_selected
import listenup.composeapp.generated.resources.connect_add_server_manually
import listenup.composeapp.generated.resources.connect_enter_server_url_directly
import listenup.composeapp.generated.resources.connect_make_sure_your_listenup_server
import listenup.composeapp.generated.resources.connect_on_your_network
import listenup.composeapp.generated.resources.connect_rescan
import listenup.composeapp.generated.resources.connect_select_server
import listenup.composeapp.generated.resources.connect_select_server_subtitle
import listenup.composeapp.generated.resources.connect_version_prefix
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Server selection — the first screen of the connect flow. Lists servers discovered via mDNS
 * (plus previously connected ones), with a manual-entry escape hatch and a rescan control.
 * Renders through the shared [AuthScaffold].
 *
 * @param onServerActivated Invoked when a selected server is activated.
 * @param onManualEntryRequested Invoked when the user opts to enter a URL manually.
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

    // Guard: forward the permission result exactly once per ViewModel lifetime.
    // rememberSaveable (not remember) survives configuration changes — the same
    // ViewModel instance continues after rotation, so without this guard a
    // recomposition would fire a second permission callback and double-start
    // discovery (or worse, trigger a spurious denial → navigate-to-manual-entry).
    var permissionResolved by rememberSaveable { mutableStateOf(false) }
    if (!permissionResolved) {
        RequestLocalNetworkPermission { granted ->
            permissionResolved = true
            if (granted) {
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionGranted)
            } else {
                viewModel.onEvent(ServerSelectUiEvent.LocalNetworkPermissionDenied)
            }
        }
    }

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

    Box(modifier = modifier.fillMaxSize()) {
        AuthScaffold(
            title = stringResource(Res.string.connect_select_server),
            subtitle = stringResource(Res.string.connect_select_server_subtitle),
        ) {
            val isDiscovering = state is ServerSelectUiState.Discovering
            val connectingId = (state as? ServerSelectUiState.Connecting)?.selectedServerId
            val selectedId =
                when (val current = state) {
                    is ServerSelectUiState.Connecting -> current.selectedServerId
                    is ServerSelectUiState.Error -> current.selectedServerId
                    else -> null
                }

            NetworkHeader(
                count = state.servers.size,
                isDiscovering = isDiscovering,
                onRescan = { viewModel.onEvent(ServerSelectUiEvent.RefreshClicked) },
            )

            state.servers.forEach { serverWithStatus ->
                ServerRow(
                    serverWithStatus = serverWithStatus,
                    isSelected = selectedId == serverWithStatus.server.id,
                    isConnecting = connectingId == serverWithStatus.server.id,
                    onClick = { viewModel.onEvent(ServerSelectUiEvent.ServerSelected(serverWithStatus)) },
                )
            }

            if (state.servers.isEmpty() && !isDiscovering) {
                EmptyState()
            }

            AddServerRow(onClick = { viewModel.onEvent(ServerSelectUiEvent.ManualEntryClicked) })
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
        )
    }
}

@Composable
private fun NetworkHeader(
    count: Int,
    isDiscovering: Boolean,
    onRescan: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.connect_on_your_network).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CountBadge(count)
        Box(modifier = Modifier.weight(1f))
        AssistChip(
            onClick = onRescan,
            enabled = !isDiscovering,
            label = { Text(stringResource(Res.string.connect_rescan)) },
            leadingIcon = {
                if (isDiscovering) {
                    ListenUpLoadingIndicatorSmall()
                } else {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(Res.string.common_refresh),
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun CountBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ServerRow(
    serverWithStatus: ServerWithStatus,
    isSelected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit,
) {
    val server = serverWithStatus.server
    val onRow =
        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val onRowMuted =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        onClick = onClick,
        enabled = !isConnecting,
        shape = MaterialTheme.shapes.large,
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Dns,
                        contentDescription = null,
                        tint =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = onRow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                server.getBestUrl()?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = onRowMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusRow(
                    isOnline = serverWithStatus.isOnline,
                    version = server.serverVersion,
                    onRowMuted = onRowMuted,
                )
            }

            ServerRowTrailing(isSelected = isSelected, isConnecting = isConnecting)
        }
    }
}

@Composable
private fun StatusRow(
    isOnline: Boolean,
    version: String,
    onRowMuted: Color,
) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = if (isOnline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(8.dp),
            content = {},
        )
        Text(
            text = stringResource(if (isOnline) Res.string.common_online else Res.string.common_offline),
            style = MaterialTheme.typography.bodySmall,
            color = onRowMuted,
        )
        version.takeIf { it != "unknown" }?.let {
            Text(
                text = stringResource(Res.string.connect_version_prefix, it),
                style = MaterialTheme.typography.bodySmall,
                color = onRowMuted.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ServerRowTrailing(
    isSelected: Boolean,
    isConnecting: Boolean,
) {
    when {
        isConnecting -> {
            ListenUpLoadingIndicatorSmall()
        }

        isSelected -> {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = stringResource(Res.string.common_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        else -> {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddServerRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
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
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.common_no_items_found, "servers"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.connect_make_sure_your_listenup_server),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
