package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreBackupViewModel
import com.calypsan.listenup.client.presentation.error.localized
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_backup
import listenup.composeapp.generated.resources.admin_restore_backup
import listenup.composeapp.generated.resources.admin_restored_from
import listenup.composeapp.generated.resources.admin_schema_migrated
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreBackupScreen(
    backupId: String,
    viewModel: RestoreBackupViewModel = koinViewModel { parametersOf(backupId) },
    onBackClick: () -> Unit,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    // Once the restore is in flight or done, the only way out is via the
    // Restoring spinner finishing or the Completed "Done" button. Hide back
    // navigation in those states to avoid abandoning a destructive operation.
    val canNavigateBack = state is RestoreBackupUiState.Idle

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.admin_restore_backup)) },
                navigationIcon = {
                    if (canNavigateBack) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.common_back),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val s = state) {
            is RestoreBackupUiState.Idle -> {
                IdleContent(
                    backupId = backupId,
                    error = s.error,
                    onRestoreClick = viewModel::requestRestore,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            RestoreBackupUiState.Confirming -> {
                // Keep the idle content beneath the confirmation dialog so the
                // screen never blanks while the user decides.
                IdleContent(
                    backupId = backupId,
                    error = null,
                    onRestoreClick = viewModel::requestRestore,
                    modifier = Modifier.padding(paddingValues),
                )
                ListenUpDestructiveDialog(
                    onDismissRequest = viewModel::cancelRestore,
                    title = "Restore Backup?",
                    text =
                        "This replaces everything on this server, including all user accounts, with the " +
                            "contents of this backup. You'll be signed out and must sign in again with an " +
                            "account from this backup. This cannot be undone.",
                    confirmText = "Restore",
                    onConfirm = viewModel::confirmRestore,
                    icon = Icons.Default.Warning,
                )
            }

            RestoreBackupUiState.Restoring -> {
                FullScreenLoadingIndicator(
                    message = restoreStatusLabel(progress),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            is RestoreBackupUiState.Completed -> {
                CompletedContent(
                    result = s.result,
                    onDone = onComplete,
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    backupId: String,
    error: AppError?,
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                HeaderRow(
                    icon = Icons.Default.Warning,
                    title = "Destructive action",
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        "Restoring replaces all current server data with the contents of this " +
                            "backup. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(Res.string.admin_backup),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = backupId,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        error?.let { ErrorCard(text = it.localized()) }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onRestoreClick,
            text = "Restore this backup",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompletedContent(
    result: RestoreResult,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                HeaderRow(
                    icon = Icons.Default.CheckCircle,
                    title = "Restore complete",
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.admin_restored_from, result.restoredFrom.value),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text =
                        stringResource(
                            Res.string.admin_schema_migrated,
                            result.schemaMigratedFrom,
                            result.schemaMigratedTo,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text =
                        if (result.includedImages) {
                            "Cover images and avatars were included."
                        } else {
                            "Cover images and avatars were not included."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onDone,
            text = "Done",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Maps the live [BackupEvent] restore progress into a short status label. */
private fun restoreStatusLabel(event: BackupEvent?): String =
    when (event) {
        BackupEvent.Validating -> "Validating backup..."
        BackupEvent.Draining -> "Finishing in-flight requests..."
        BackupEvent.Swapping -> "Swapping in the restored database..."
        BackupEvent.Migrating -> "Migrating to the current schema..."
        is BackupEvent.RestoreComplete -> "Finishing up..."
        is BackupEvent.RolledBack -> "Rolling back..."
        else -> "Restoring backup..."
    }

@Composable
private fun HeaderRow(
    icon: ImageVector,
    title: String,
    contentColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun ErrorCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
