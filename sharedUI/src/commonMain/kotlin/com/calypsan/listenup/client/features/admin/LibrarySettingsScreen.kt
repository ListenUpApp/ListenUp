package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_access_mode
import listenup.composeapp.generated.resources.admin_add_folder
import listenup.composeapp.generated.resources.admin_add_this_folder
import listenup.composeapp.generated.resources.admin_inbox_settings
import listenup.composeapp.generated.resources.admin_library_name
import listenup.composeapp.generated.resources.admin_new_books_in_this_library
import listenup.composeapp.generated.resources.admin_remove_path
import listenup.composeapp.generated.resources.admin_remove_path_from_library_scan
import listenup.composeapp.generated.resources.admin_remove_scan_path
import listenup.composeapp.generated.resources.admin_rescan_library
import listenup.composeapp.generated.resources.admin_scan_all_paths_for_new
import listenup.composeapp.generated.resources.admin_scan_paths
import listenup.composeapp.generated.resources.admin_scanning
import listenup.composeapp.generated.resources.admin_select_folder
import listenup.composeapp.generated.resources.admin_skip_inbox
import listenup.composeapp.generated.resources.admin_uncollected_books_are_visible_to
import listenup.composeapp.generated.resources.admin_users_only_see_books_in
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_entity_information
import listenup.composeapp.generated.resources.common_open
import listenup.composeapp.generated.resources.common_remove
import listenup.composeapp.generated.resources.common_restricted
import org.jetbrains.compose.resources.stringResource

/**
 * Screen for viewing and editing a library's settings.
 *
 * Features:
 * - View library information (name, scan paths)
 * - Toggle access mode (open vs restricted)
 * - Toggle skip inbox setting
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    viewModel: LibrarySettingsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? LibrarySettingsUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val topBarTitle =
        when (val s = state) {
            is LibrarySettingsUiState.Ready -> s.library.name
            is LibrarySettingsUiState.Loading, is LibrarySettingsUiState.Error -> "Library Settings"
        }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LibrarySettingsBody(
            state = state,
            innerPadding = innerPadding,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun LibrarySettingsBody(
    state: LibrarySettingsUiState,
    innerPadding: PaddingValues,
    viewModel: LibrarySettingsViewModel,
) {
    when (state) {
        is LibrarySettingsUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is LibrarySettingsUiState.Error -> {
            ErrorContent(
                message = state.message,
                modifier = Modifier.padding(innerPadding),
            )
        }

        is LibrarySettingsUiState.Ready -> {
            LibrarySettingsContent(
                state = state,
                onAccessModeChange = viewModel::setAccessMode,
                onToggleSkipInbox = viewModel::toggleSkipInbox,
                onRemoveScanPath = viewModel::removeScanPath,
                onAddFolder = { viewModel.setShowFolderBrowser(true) },
                onTriggerScan = viewModel::triggerScan,
                modifier = Modifier.padding(innerPadding),
            )

            // Folder browser dialog
            if (state.showFolderBrowser) {
                FolderBrowserDialog(
                    state = state,
                    onDismiss = { viewModel.setShowFolderBrowser(false) },
                    onNavigate = viewModel::loadBrowserDirectory,
                    onNavigateUp = viewModel::browserNavigateUp,
                    onSelectPath = viewModel::addScanPath,
                )
            }
        }
    }
}

@Composable
private fun LibrarySettingsContent(
    state: LibrarySettingsUiState.Ready,
    onAccessModeChange: (AccessMode) -> Unit,
    onToggleSkipInbox: () -> Unit,
    onRemoveScanPath: (String) -> Unit,
    onAddFolder: () -> Unit,
    onTriggerScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = state.library

    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        // Library info section
        item {
            Text(
                text = stringResource(Res.string.common_entity_information, "Library"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
        }

        item {
            LibraryInfoCard(library = library)
        }

        // Scan paths section
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.admin_scan_paths),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        item {
            ScanPathsCard(
                scanPaths = library.scanPaths,
                isSaving = state.isSaving,
                onRemovePath = onRemoveScanPath,
                onAddFolder = onAddFolder,
            )
        }

        // Rescan section
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.admin_scanning),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        item {
            RescanCard(
                isScanning = state.isScanning,
                onTriggerScan = onTriggerScan,
            )
        }

        // Access mode section
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.admin_access_mode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        item {
            AccessModeCard(
                currentMode = state.accessMode,
                isSaving = state.isSaving,
                onModeSelected = onAccessModeChange,
            )
        }

        // Inbox settings section
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.admin_inbox_settings),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        item {
            InboxSettingsCard(
                skipInbox = state.skipInbox,
                isSaving = state.isSaving,
                onToggleSkipInbox = onToggleSkipInbox,
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LibraryInfoCard(
    library: Library,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Name row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.admin_library_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (library.scanPaths.isNotEmpty()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                // Scan paths
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.admin_scan_paths),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    library.scanPaths.forEach { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessModeCard(
    currentMode: AccessMode,
    isSaving: Boolean,
    onModeSelected: (AccessMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column {
            // Open mode option
            AccessModeRow(
                icon = Icons.Outlined.LockOpen,
                title = stringResource(Res.string.common_open),
                description = stringResource(Res.string.admin_uncollected_books_are_visible_to),
                isSelected = currentMode == AccessMode.OPEN,
                isEnabled = !isSaving,
                onClick = { onModeSelected(AccessMode.OPEN) },
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Restricted mode option
            AccessModeRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(Res.string.common_restricted),
                description = stringResource(Res.string.admin_users_only_see_books_in),
                isSelected = currentMode == AccessMode.RESTRICTED,
                isEnabled = !isSaving,
                onClick = { onModeSelected(AccessMode.RESTRICTED) },
            )
        }
    }
}

@Composable
private fun AccessModeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    isSelected: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = isEnabled, onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint =
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector =
                if (isSelected) {
                    Icons.Outlined.RadioButtonChecked
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
            contentDescription =
                if (isSelected) {
                    "Selected"
                } else {
                    "Not selected"
                },
            tint =
                if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun InboxSettingsCard(
    skipInbox: Boolean,
    isSaving: Boolean,
    onToggleSkipInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.admin_skip_inbox),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.admin_new_books_in_this_library),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSaving) {
                ListenUpLoadingIndicatorSmall()
            } else {
                Switch(
                    checked = skipInbox,
                    onCheckedChange = { onToggleSkipInbox() },
                )
            }
        }
    }
}

@Composable
private fun ScanPathsCard(
    scanPaths: List<String>,
    isSaving: Boolean,
    onRemovePath: (String) -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pathToRemove by remember { mutableStateOf<String?>(null) }

    // Confirm removal dialog
    pathToRemove?.let { path ->
        AlertDialog(
            onDismissRequest = { pathToRemove = null },
            title = { Text(stringResource(Res.string.admin_remove_scan_path)) },
            text = { Text(stringResource(Res.string.admin_remove_path_from_library_scan, path)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemovePath(path)
                    pathToRemove = null
                }) {
                    Text(stringResource(Res.string.common_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pathToRemove = null }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            scanPaths.forEachIndexed { index, path ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (scanPaths.size > 1 && !isSaving) {
                        IconButton(onClick = { pathToRemove = path }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(Res.string.admin_remove_path),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (index < scanPaths.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Add folder button
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSaving, onClick = onAddFolder)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.admin_add_folder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RescanCard(
    isScanning: Boolean,
    onTriggerScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isScanning, onClick = onTriggerScan)
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.admin_rescan_library),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.admin_scan_all_paths_for_new),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isScanning) {
                ListenUpLoadingIndicatorSmall()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderBrowserDialog(
    state: LibrarySettingsUiState.Ready,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectPath: (String) -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().height(500.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                TopAppBar(
                    title = { Text(stringResource(Res.string.admin_select_folder)) },
                    navigationIcon = {
                        if (!state.browserIsRoot) {
                            IconButton(onClick = onNavigateUp) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, "Close")
                        }
                    },
                )

                // Current path
                Text(
                    text = state.browserPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // Select current folder button
                TextButton(
                    onClick = { onSelectPath(state.browserPath) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(Res.string.admin_add_this_folder))
                }

                HorizontalDivider()

                if (state.isBrowserLoading) {
                    FullScreenLoadingIndicator()
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(
                            count = state.browserEntries.size,
                            key = { state.browserEntries[it].path },
                        ) { index ->
                            val entry = state.browserEntries[index]
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigate(entry.path) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
