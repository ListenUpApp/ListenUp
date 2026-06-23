package com.calypsan.listenup.client.features.admin.backup

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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.calypsan.listenup.client.design.components.ActionTile
import com.calypsan.listenup.client.design.components.ListenUpFab
import com.calypsan.listenup.client.design.components.ScallopBadge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import listenup.composeapp.generated.resources.common_books_count
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.core.BackupId
import kotlinx.io.asSink
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel
import com.calypsan.listenup.client.presentation.error.localized
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_backup_created_at
import listenup.composeapp.generated.resources.admin_backup_download_failed
import listenup.composeapp.generated.resources.admin_backup_downloaded
import listenup.composeapp.generated.resources.admin_backup_size
import listenup.composeapp.generated.resources.admin_backups
import listenup.composeapp.generated.resources.admin_confirm_delete_backup
import listenup.composeapp.generated.resources.admin_create_backup
import listenup.composeapp.generated.resources.admin_create_backup_to_protect
import listenup.composeapp.generated.resources.admin_delete_backup
import listenup.composeapp.generated.resources.admin_download_backup
import listenup.composeapp.generated.resources.admin_migrate_listening_history
import listenup.composeapp.generated.resources.admin_no_backups_yet
import listenup.composeapp.generated.resources.admin_restore
import listenup.composeapp.generated.resources.admin_restore_from_file
import listenup.composeapp.generated.resources.admin_restore_from_file_description
import listenup.composeapp.generated.resources.admin_upload_new_import
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_menu
import listenup.composeapp.generated.resources.common_open
import listenup.composeapp.generated.resources.import_audiobookshelf_imports
import listenup.composeapp.generated.resources.import_delete_confirm
import listenup.composeapp.generated.resources.import_delete_import
import listenup.composeapp.generated.resources.import_flow_eyebrow
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
    onRestoreFromFileClick: () -> Unit = {},
    onABSImportHubClick: (String) -> Unit,
    onNewImportClick: () -> Unit = {},
) {
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val absImportListState by absImportViewModel.listState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val downloadSavedMessage = stringResource(Res.string.admin_backup_downloaded)
    val downloadFailedMessage = stringResource(Res.string.admin_backup_download_failed)

    // The backup chosen for download — set when the user taps Download, consumed when the
    // Save-As picker returns a destination URI.
    var pendingDownloadBackup by remember { mutableStateOf<BackupInfo?>(null) }

    val saveBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val backup = pendingDownloadBackup
            pendingDownloadBackup = null
            if (uri == null || backup == null) return@rememberLauncherForActivityResult
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                Toast.makeText(context, downloadFailedMessage, Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            backupViewModel.downloadBackup(BackupId(backup.id), outputStream.asSink())
        }

    // Confirm a completed download with a toast.
    LaunchedEffect(Unit) {
        backupViewModel.downloadSaved.collect {
            Toast.makeText(context, downloadSavedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    // Delete import confirmation state
    var deleteConfirmImport by remember { mutableStateOf<ImportSummary?>(null) }

    val readyState = backupState as? AdminBackupUiState.Ready

    ListenUpScaffold(
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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            ColorBlockHero(
                title = stringResource(Res.string.admin_backups),
                badgeIcon = Icons.Default.Archive,
                onBack = onBackClick,
                modifier = Modifier.fillMaxWidth(),
                overline = stringResource(Res.string.import_flow_eyebrow),
            )
            AdminBackupBody(
                state = backupState,
                absImports = absListReady?.imports ?: emptyList(),
                isLoadingImports = absImportListState is ABSImportListUiState.Loading,
                modifier = Modifier.fillMaxSize(),
                onRestoreClick = onRestoreClick,
                onRestoreFromFileClick = onRestoreFromFileClick,
                onDeleteClick = { backupViewModel.showDeleteConfirmation(it) },
                onDownloadClick = { backup ->
                    pendingDownloadBackup = backup
                    saveBackupLauncher.launch("${backup.id}.listenup.zip")
                },
                onABSImportClick = onABSImportHubClick,
                onDeleteImportClick = { deleteConfirmImport = it },
                onUploadABSBackup = onNewImportClick,
            )
        }
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
                Text(stringResource(Res.string.import_delete_confirm, import.id.value))
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
    absImports: List<ImportSummary>,
    isLoadingImports: Boolean,
    modifier: Modifier = Modifier,
    onRestoreClick: (String) -> Unit,
    onRestoreFromFileClick: () -> Unit,
    onDeleteClick: (BackupInfo) -> Unit,
    onDownloadClick: (BackupInfo) -> Unit,
    onABSImportClick: (String) -> Unit,
    onDeleteImportClick: (ImportSummary) -> Unit,
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
                onRestoreFromFileClick = onRestoreFromFileClick,
                onDeleteClick = onDeleteClick,
                onDownloadClick = onDownloadClick,
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
    absImports: List<ImportSummary>,
    isLoadingImports: Boolean,
    modifier: Modifier = Modifier,
    onRestoreClick: (String) -> Unit,
    onRestoreFromFileClick: () -> Unit,
    onDeleteClick: (BackupInfo) -> Unit,
    onDownloadClick: (BackupInfo) -> Unit,
    onABSImportClick: (String) -> Unit,
    onDeleteImportClick: (ImportSummary) -> Unit,
    onUploadABSBackup: () -> Unit,
) {
    if (state.backups.isEmpty() && absImports.isEmpty()) {
        EmptyBackupState(
            modifier = modifier,
            onUploadABSBackup = onUploadABSBackup,
            onRestoreFromFileClick = onRestoreFromFileClick,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Backups section
        item(key = "backups_header") {
            SectionHeader(title = stringResource(Res.string.admin_backups))
        }

        // Restore-from-file card — prominent entry point, always shown at the top of the section.
        item(key = "restore_from_file") {
            RestoreFromFileCard(onClick = onRestoreFromFileClick)
        }

        if (state.backups.isNotEmpty()) {
            items(state.backups, key = { "backup_${it.id}" }) { backup ->
                BackupCard(
                    backup = backup,
                    onRestoreClick = { onRestoreClick(backup.id) },
                    onDownloadClick = { onDownloadClick(backup) },
                    onDeleteClick = { onDeleteClick(backup) },
                )
            }
        }

        // ABS Imports section
        item(key = "abs_header") {
            if (state.backups.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            SectionHeader(title = stringResource(Res.string.import_audiobookshelf_imports))
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
                    onClick = { onABSImportClick(import.id.value) },
                    onDeleteClick = { onDeleteImportClick(import) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun BackupCard(
    backup: BackupInfo,
    onRestoreClick: () -> Unit,
    onDownloadClick: () -> Unit,
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
                            text = { Text(stringResource(Res.string.admin_download_backup)) },
                            onClick = {
                                showMenu = false
                                onDownloadClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
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
 * Color-blocked navigation tile for restoring a ListenUp backup from a local file — peer to
 * [UploadABSBackupCard] so this entry point is equally prominent in the Backups section.
 */
@Composable
private fun RestoreFromFileCard(onClick: () -> Unit) {
    ActionTile(
        title = stringResource(Res.string.admin_restore_from_file),
        subtitle = stringResource(Res.string.admin_restore_from_file_description),
        icon = Icons.Default.Restore,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        badgeColor = MaterialTheme.colorScheme.secondary,
        badgeContentColor = MaterialTheme.colorScheme.onSecondary,
    )
}

/**
 * Color-blocked navigation tile for uploading a new ABS backup file — composes the canonical
 * [ActionTile] so the upload call-to-action matches the app's other big management tiles.
 */
@Composable
private fun UploadABSBackupCard(onClick: () -> Unit) {
    ActionTile(
        title = stringResource(Res.string.admin_upload_new_import),
        subtitle = stringResource(Res.string.admin_migrate_listening_history),
        icon = Icons.Outlined.CloudUpload,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        badgeColor = MaterialTheme.colorScheme.primary,
        badgeContentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

/**
 * Card for a staged ABS import: created-at timestamp, status, and book count. Tapping resumes into
 * the linear import flow; the overflow menu deletes the staged job.
 */
@Composable
private fun ABSImportSummaryCard(
    import: ImportSummary,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val localDateTime =
        Instant
            .fromEpochMilliseconds(import.createdAt)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    val timeStr = "${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')}"

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
                    text = stringResource(Res.string.admin_backup_created_at, localDateTime.date.toString(), timeStr),
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

            Text(
                text = stringResource(Res.string.common_books_count, import.bookCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBadge(status: ImportStatus) {
    val (containerColor, contentColor, label) =
        when (status) {
            ImportStatus.UPLOADED -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Uploaded",
                )
            }

            ImportStatus.ANALYZED -> {
                Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "Analyzed",
                )
            }

            ImportStatus.MAPPED -> {
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Mapped",
                )
            }

            ImportStatus.APPLIED -> {
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    "Applied",
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
    onRestoreFromFileClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScallopBadge(size = 104.dp, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(46.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = stringResource(Res.string.admin_no_backups_yet),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.admin_create_backup_to_protect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        RestoreFromFileCard(onClick = onRestoreFromFileClick)
        Spacer(modifier = Modifier.height(12.dp))
        UploadABSBackupCard(onClick = onUploadABSBackup)
    }
}
