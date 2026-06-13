package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.calypsan.listenup.client.design.components.ListenUpFab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.data.remote.ABSImportSummary
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.util.rememberABSBackupPicker
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_backup_created_at
import listenup.composeapp.generated.resources.admin_backup_size
import listenup.composeapp.generated.resources.admin_backups
import listenup.composeapp.generated.resources.admin_confirm_delete_backup
import listenup.composeapp.generated.resources.admin_create_backup
import listenup.composeapp.generated.resources.admin_create_backup_to_protect
import listenup.composeapp.generated.resources.admin_delete_backup
import listenup.composeapp.generated.resources.admin_import_books_progress
import listenup.composeapp.generated.resources.admin_import_sessions_progress
import listenup.composeapp.generated.resources.admin_import_users_progress
import listenup.composeapp.generated.resources.admin_migrate_listening_history
import listenup.composeapp.generated.resources.admin_no_backups_yet
import listenup.composeapp.generated.resources.admin_restore
import listenup.composeapp.generated.resources.admin_upload_new_import
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_menu
import listenup.composeapp.generated.resources.common_open
import listenup.composeapp.generated.resources.import_delete_confirm
import listenup.composeapp.generated.resources.import_delete_import
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun AdminBackupScreen(
    backupViewModel: AdminBackupViewModel = koinViewModel(),
    absImportViewModel: ABSImportHubViewModel = koinViewModel(),
    onBackClick: () -> Unit,
    onCreateClick: () -> Unit,
    onRestoreClick: (String) -> Unit,
    onABSImportHubClick: (String) -> Unit,
    onNewImportClick: () -> Unit = {},
) {
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val absImportListState by absImportViewModel.listState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Delete import confirmation state
    var deleteConfirmImport by remember { mutableStateOf<ABSImportSummary?>(null) }

    // Upload sheet state
    var showUploadSheet by remember { mutableStateOf(false) }
    val uploadSheetState = rememberABSUploadSheetState()

    // Document picker for ABS backup files
    val documentPicker =
        rememberABSBackupPicker { result ->
            uploadSheetState.onDocumentSelected(result)
        }

    // Restore any in-flight upload job after app restart
    LaunchedEffect(Unit) {
        uploadSheetState.restoreActiveWorkIfRunning(context)
    }

    // Observe WorkManager work info when an upload is active
    LaunchedEffect(uploadSheetState.activeWorkId) {
        val flow = uploadSheetState.getWorkInfoFlow(context) ?: return@LaunchedEffect
        flow.collect { workInfo ->
            uploadSheetState.observeWorkInfo(workInfo)
        }
    }

    // Auto-navigate to import detail when background upload completes while on this screen
    LaunchedEffect(uploadSheetState.uploadState) {
        val currentState = uploadSheetState.uploadState
        if (currentState is ABSUploadState.Complete && currentState.importId.isNotEmpty()) {
            uploadSheetState.reset()
            onABSImportHubClick(currentState.importId)
        }
    }

    val readyState = backupState as? AdminBackupUiState.Ready

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.admin_backups)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (readyState != null) {
                ListenUpFab(
                    onClick = onCreateClick,
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(Res.string.admin_create_backup),
                )
            }
        },
    ) { paddingValues ->
        val absListReady = absImportListState as? ABSImportListUiState.Ready
        AdminBackupBody(
            state = backupState,
            absImports = absListReady?.imports ?: emptyList(),
            isLoadingImports = absImportListState is ABSImportListUiState.Loading,
            modifier = Modifier.padding(paddingValues),
            onRestoreClick = onRestoreClick,
            onDeleteClick = { backupViewModel.showDeleteConfirmation(it) },
            onABSImportClick = onABSImportHubClick,
            onDeleteImportClick = { deleteConfirmImport = it },
            onUploadABSBackup = onNewImportClick,
        )
    }

    // ABS Upload Sheet
    if (showUploadSheet) {
        ABSUploadSheet(
            state = uploadSheetState.uploadState,
            onPickFile = { documentPicker.launch() },
            onUpload = {
                // Sheet stays visible during upload — shows existing progress UI.
                // Upload runs via foreground service so user can dismiss freely.
                uploadSheetState.enqueueUpload(context)
            },
            onNavigateToImport = { importId ->
                showUploadSheet = false
                uploadSheetState.reset()
                onABSImportHubClick(importId)
            },
            onDismiss = {
                showUploadSheet = false
                uploadSheetState.reset()
            },
            onRetry = {
                uploadSheetState.retry()
                documentPicker.launch()
            },
        )
    }

    // Delete backup confirmation dialog (only meaningful when Ready).
    readyState?.deleteConfirmBackup?.let { backup ->
        DeleteBackupDialog(
            backup = backup,
            onConfirm = { backupViewModel.deleteBackup(backup) },
            onDismiss = { backupViewModel.dismissDeleteConfirmation() },
        )
    }

    // Delete import confirmation dialog
    deleteConfirmImport?.let { import ->
        AlertDialog(
            onDismissRequest = { deleteConfirmImport = null },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.import_delete_import)) },
            text = {
                Text(stringResource(Res.string.import_delete_confirm, import.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        absImportViewModel.deleteImport(import.id)
                        deleteConfirmImport = null
                    },
                ) {
                    Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmImport = null }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun DeleteBackupDialog(
    backup: BackupInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.admin_delete_backup)) },
        text = { Text(stringResource(Res.string.admin_confirm_delete_backup, backup.id)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

@Composable
private fun AdminBackupBody(
    state: AdminBackupUiState,
    absImports: List<ABSImportSummary>,
    isLoadingImports: Boolean,
    modifier: Modifier = Modifier,
    onRestoreClick: (String) -> Unit,
    onDeleteClick: (BackupInfo) -> Unit,
    onABSImportClick: (String) -> Unit,
    onDeleteImportClick: (ABSImportSummary) -> Unit,
    onUploadABSBackup: () -> Unit,
) {
    when (state) {
        is AdminBackupUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is AdminBackupUiState.Error -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error.localized(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is AdminBackupUiState.Ready -> {
            AdminBackupReadyContent(
                state = state,
                absImports = absImports,
                isLoadingImports = isLoadingImports,
                modifier = modifier,
                onRestoreClick = onRestoreClick,
                onDeleteClick = onDeleteClick,
                onABSImportClick = onABSImportClick,
                onDeleteImportClick = onDeleteImportClick,
                onUploadABSBackup = onUploadABSBackup,
            )
        }
    }
}

@Composable
private fun AdminBackupReadyContent(
    state: AdminBackupUiState.Ready,
    absImports: List<ABSImportSummary>,
    isLoadingImports: Boolean,
    modifier: Modifier = Modifier,
    onRestoreClick: (String) -> Unit,
    onDeleteClick: (BackupInfo) -> Unit,
    onABSImportClick: (String) -> Unit,
    onDeleteImportClick: (ABSImportSummary) -> Unit,
    onUploadABSBackup: () -> Unit,
) {
    if (state.backups.isEmpty() && absImports.isEmpty()) {
        EmptyBackupState(
            modifier = modifier,
            onUploadABSBackup = onUploadABSBackup,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Backups section
        if (state.backups.isNotEmpty()) {
            item(key = "backups_header") {
                SectionHeader(title = stringResource(Res.string.admin_backups))
            }
            items(state.backups, key = { "backup_${it.id}" }) { backup ->
                BackupCard(
                    backup = backup,
                    onRestoreClick = { onRestoreClick(backup.id) },
                    onDeleteClick = { onDeleteClick(backup) },
                )
            }
        }

        // ABS Imports section
        item(key = "abs_header") {
            if (state.backups.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            SectionHeader(title = "Audiobookshelf Imports")
        }

        // Upload new import card
        item(key = "upload_new") {
            UploadABSBackupCard(onClick = onUploadABSBackup)
        }

        // Existing imports
        if (isLoadingImports) {
            item(key = "loading_imports") {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ListenUpLoadingIndicatorSmall()
                }
            }
        } else {
            items(absImports, key = { "import_${it.id}" }) { import ->
                ABSImportSummaryCard(
                    import = import,
                    onClick = { onABSImportClick(import.id) },
                    onDeleteClick = { onDeleteImportClick(import) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun BackupCard(
    backup: BackupInfo,
    onRestoreClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = backup.id,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.common_menu))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.admin_restore)) },
                            onClick = {
                                showMenu = false
                                onRestoreClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.common_delete)) },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val localDateTime =
                Instant
                    .fromEpochMilliseconds(backup.createdAt.epochMillis)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
            val timeStr = "${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')}"
            Text(
                text = stringResource(Res.string.admin_backup_created_at, localDateTime.date.toString(), timeStr),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.admin_backup_size, backup.sizeFormatted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Card for uploading a new ABS backup file.
 */
@Composable
private fun UploadABSBackupCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(Res.string.admin_upload_new_import),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(Res.string.admin_migrate_listening_history),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Card showing an existing ABS import with progress.
 */
@Composable
private fun ABSImportSummaryCard(
    import: ABSImportSummary,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val isActive = import.status.lowercase() == "active"
    val progress =
        if (import.totalSessions > 0) {
            import.sessionsImported.toFloat() / import.totalSessions.toFloat()
        } else {
            0f
        }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = import.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(status = import.status)
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.common_menu))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.common_open)) },
                                onClick = {
                                    showMenu = false
                                    onClick()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.common_delete)) },
                                onClick = {
                                    showMenu = false
                                    onDeleteClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress info
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            Res.string.admin_import_users_progress,
                            import.usersMapped,
                            import.totalUsers,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        stringResource(
                            Res.string.admin_import_books_progress,
                            import.booksMapped,
                            import.totalBooks,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        stringResource(
                            Res.string.admin_import_sessions_progress,
                            import.sessionsImported,
                            import.totalSessions,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Progress bar for active imports
            if (isActive && progress < 1f) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (containerColor, contentColor, label) =
        when (status.lowercase()) {
            "active" -> {
                Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "Active",
                )
            }

            "completed" -> {
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    "Completed",
                )
            }

            "archived" -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Archived",
                )
            }

            else -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    status,
                )
            }
        }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyBackupState(
    modifier: Modifier = Modifier,
    onUploadABSBackup: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Archive,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.admin_no_backups_yet),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.admin_create_backup_to_protect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(32.dp))
        UploadABSBackupCard(onClick = onUploadABSBackup)
    }
}
