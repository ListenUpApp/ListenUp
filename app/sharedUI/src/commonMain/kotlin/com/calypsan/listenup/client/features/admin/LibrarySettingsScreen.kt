package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CardDefaults
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import com.calypsan.listenup.client.design.components.SectionColumns
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpScaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.theme.Spacing
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
        readyError?.let { error ->
            snackbarHostState.showSnackbar(error.localizedString())
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

/**
 * The screen's loaded body, hosted by its scaffold. A phone stacks the scan paths over the scanning
 * controls; from the medium width up they sit side by side in [SectionColumns], the folders beside
 * the scan that walks them.
 */
@Composable
internal fun LibrarySettingsContent(
    state: LibrarySettingsUiState.Ready,
    onRemoveFolder: (String) -> Unit,
    onAddFolder: () -> Unit,
    onTriggerScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var folderToRemove by remember { mutableStateOf<LibraryFolderRef?>(null) }

    // Confirm removal dialog
    folderToRemove?.let { folder ->
        ListenUpAlertDialog(
            onDismissRequest = { folderToRemove = null },
            title = stringResource(Res.string.admin_remove_scan_path),
            text = stringResource(Res.string.admin_remove_path_from_library_scan, folder.rootPath ?: folder.id),
            confirmText = stringResource(Res.string.common_remove),
            onConfirm = {
                onRemoveFolder(folder.id)
                folderToRemove = null
            },
            dismissText = stringResource(Res.string.common_cancel),
            onDismiss = { folderToRemove = null },
        )
    }

    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    val onRemoveRequest: (LibraryFolderRef) -> Unit = { folderToRemove = it }
    if (isWide) {
        SectionColumns(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin, vertical = 24.dp),
        ) {
            section { ScanPathsSection(state = state, onRemoveRequest = onRemoveRequest, onAddFolder = onAddFolder) }
            section { ScanningSection(isScanning = state.isScanning, onTriggerScan = onTriggerScan) }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.screenMargin, vertical = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            item { ScanPathsSection(state = state, onRemoveRequest = onRemoveRequest, onAddFolder = onAddFolder) }
            item { ScanningSection(isScanning = state.isScanning, onTriggerScan = onTriggerScan) }
        }
    }
}

/** The library's folders, each removable while another remains, plus the add-folder row. */
@Composable
private fun ScanPathsSection(
    state: LibrarySettingsUiState.Ready,
    onRemoveRequest: (LibraryFolderRef) -> Unit,
    onAddFolder: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val library = state.library
    SectionGroup(
        label = stringResource(Res.string.admin_scan_paths),
    ) {
        library.folders.forEach { folder ->
            val canRemove = library.folders.size > 1 && !state.isSaving
            SettingRow(
                title = folder.rootPath ?: folder.id,
                icon = Icons.Outlined.Folder,
                accent = MaterialTheme.colorScheme.secondary,
                trailing =
                    if (canRemove) {
                        {
                            IconButton(
                                onClick = {
                                    haptics.press()
                                    onRemoveRequest(folder)
                                },
                            ) {
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
            onClick = if (state.isSaving) null else onAddFolder,
        )
    }
}

/** The rescan action, showing progress while a scan runs. */
@Composable
private fun ScanningSection(
    isScanning: Boolean,
    onTriggerScan: () -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_scanning),
    ) {
        SettingRow(
            title = stringResource(Res.string.admin_rescan_library),
            subtitle = stringResource(Res.string.admin_scan_all_paths_for_new),
            icon = Icons.Outlined.Refresh,
            onClick = if (isScanning) null else onTriggerScan,
            trailing =
                if (isScanning) {
                    { ListenUpLoadingIndicatorSmall() }
                } else {
                    null
                },
        )
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
    val haptics = LocalHaptics.current
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        // A dialog is chrome: tonal, at the dialog container level, with no shadow of its own.
        Card(
            modifier = Modifier.fillMaxWidth().height(500.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                ListenUpTopAppBar(
                    title = stringResource(Res.string.admin_select_folder),
                    onBack = if (state.browserIsRoot) null else onNavigateUp,
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    actions = {
                        IconButton(
                            onClick = {
                                haptics.press()
                                onDismiss()
                            },
                        ) {
                            Icon(Icons.Outlined.Close, "Close")
                        }
                    },
                )

                // Current path
                Text(
                    text = state.browserPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
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
                                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
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
