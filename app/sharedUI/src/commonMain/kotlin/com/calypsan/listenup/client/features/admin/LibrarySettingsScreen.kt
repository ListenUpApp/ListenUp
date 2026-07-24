package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.domain.model.LibraryFolderRef
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_folder
import listenup.composeapp.generated.resources.admin_folder_saved_scanning
import listenup.composeapp.generated.resources.admin_library_settings
import listenup.composeapp.generated.resources.admin_remove_path
import listenup.composeapp.generated.resources.admin_remove_path_from_library_scan
import listenup.composeapp.generated.resources.admin_remove_scan_path
import listenup.composeapp.generated.resources.admin_rescan_library
import listenup.composeapp.generated.resources.admin_save_and_scan_folder
import listenup.composeapp.generated.resources.admin_scan_all_paths_for_new
import listenup.composeapp.generated.resources.admin_scan_paths
import listenup.composeapp.generated.resources.admin_scanning
import listenup.composeapp.generated.resources.admin_select_folder
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_remove
import org.jetbrains.compose.resources.stringResource

/**
 * Screen for viewing and editing a library's settings.
 *
 * Features:
 * - View library information (name, scan paths)
 * - Manage scan folders (add, remove, browse server filesystem)
 * - Trigger a library rescan
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
            snackbarHostState.showSnackbar(it.localizedString())
            viewModel.clearError()
        }
    }

    // One-shot "folder saved — scanning it now" confirmation.
    val folderSavedScanningMessage = stringResource(Res.string.admin_folder_saved_scanning)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                LibrarySettingsEvent.FolderSavedScanStarted -> {
                    snackbarHostState.showSnackbar(folderSavedScanningMessage)
                }
            }
        }
    }

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.admin_library_settings),
                badgeIcon = Icons.Outlined.FolderOpen,
                onBack = onBackClick,
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
                message = state.error.localized(),
                modifier = Modifier.padding(innerPadding),
            )
        }

        is LibrarySettingsUiState.Ready -> {
            LibrarySettingsContent(
                state = state,
                onRemoveFolder = viewModel::removeFolder,
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
    onRemoveFolder: (String) -> Unit,
    onAddFolder: () -> Unit,
    onTriggerScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = state.library
    var folderToRemove by remember { mutableStateOf<LibraryFolderRef?>(null) }

    // Confirm removal dialog
    folderToRemove?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToRemove = null },
            title = { Text(stringResource(Res.string.admin_remove_scan_path)) },
            text = {
                Text(stringResource(Res.string.admin_remove_path_from_library_scan, folder.rootPath ?: folder.id))
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveFolder(folder.id)
                    folderToRemove = null
                }) {
                    Text(stringResource(Res.string.common_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToRemove = null }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            SectionGroup(
                label = stringResource(Res.string.admin_scan_paths),
                icon = Icons.Outlined.Folder,
                accent = MaterialTheme.colorScheme.secondary,
            ) {
                library.folders.forEachIndexed { index, folder ->
                    val canRemove = library.folders.size > 1 && !state.isSaving
                    SettingRow(
                        title = folder.rootPath ?: folder.id,
                        icon = Icons.Outlined.Folder,
                        accent = MaterialTheme.colorScheme.secondary,
                        showDivider = index > 0,
                        trailing =
                            if (canRemove) {
                                {
                                    IconButton(onClick = { folderToRemove = folder }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = stringResource(Res.string.admin_remove_path),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                    )
                }
                SettingRow(
                    title = stringResource(Res.string.admin_add_folder),
                    icon = Icons.Outlined.Add,
                    accent = MaterialTheme.colorScheme.primary,
                    showDivider = true,
                    onClick = if (state.isSaving) null else onAddFolder,
                )
            }
        }

        item {
            SectionGroup(
                label = stringResource(Res.string.admin_scanning),
                icon = Icons.Outlined.Refresh,
                accent = MaterialTheme.colorScheme.primary,
            ) {
                SettingRow(
                    title = stringResource(Res.string.admin_rescan_library),
                    subtitle = stringResource(Res.string.admin_scan_all_paths_for_new),
                    icon = Icons.Outlined.Refresh,
                    onClick = if (state.isScanning) null else onTriggerScan,
                    trailing =
                        if (state.isScanning) {
                            { ListenUpLoadingIndicatorSmall() }
                        } else {
                            null
                        },
                )
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
                    Text(stringResource(Res.string.admin_save_and_scan_folder))
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
