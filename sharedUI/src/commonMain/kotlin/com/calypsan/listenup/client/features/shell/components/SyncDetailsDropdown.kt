package com.calypsan.listenup.client.features.shell.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.presentation.sync.PendingOperationUi
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.shell_all_synced
import listenup.composeapp.generated.resources.shell_changes_waiting_to_sync
import listenup.composeapp.generated.resources.shell_dismiss_all
import listenup.composeapp.generated.resources.shell_no_pending_changes
import listenup.composeapp.generated.resources.shell_pendingcount_pending
import listenup.composeapp.generated.resources.shell_retry_all
import listenup.composeapp.generated.resources.shell_sync_status
import listenup.composeapp.generated.resources.shell_syncing
import listenup.composeapp.generated.resources.shell_your_library_is_up_to
import org.jetbrains.compose.resources.stringResource

/**
 * Dropdown menu showing sync status details, anchored to the sync indicator.
 *
 * Displays:
 * - Current sync status (syncing/idle)
 * - Pending operations count
 * - Failed operations with retry/dismiss actions
 */
@Suppress("LongMethod")
@Composable
fun SyncDetailsDropdown(
    expanded: Boolean,
    state: SyncIndicatorUiState,
    onRetryOperation: (String) -> Unit,
    onDismissOperation: (String) -> Unit,
    onRetryAll: () -> Unit,
    onDismissAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(320.dp).heightIn(max = 400.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Header
            Text(
                text = stringResource(Res.string.shell_sync_status),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Current status
            SyncStatusSection(
                isSyncing = state.isSyncing,
                currentOperation = state.currentOperationDescription,
                pendingCount = state.pendingCount,
            )

            // Failed operations section
            if (state.failedOperations.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                FailedOperationsSection(
                    failedOperations = state.failedOperations,
                    onRetryOperation = onRetryOperation,
                    onDismissOperation = onDismissOperation,
                    onRetryAll = onRetryAll,
                    onDismissAll = onDismissAll,
                )
            }

            // Success state when nothing pending and no errors
            if (!state.isSyncing && state.pendingCount == 0 && state.failedOperations.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                SyncCompleteSection()
            }
        }
    }
}

@Composable
private fun SyncStatusSection(
    isSyncing: Boolean,
    currentOperation: String?,
    pendingCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSyncing) {
                ListenUpLoadingIndicatorSmall()
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(Res.string.shell_syncing),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    currentOperation?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (pendingCount > 0) {
                Icon(
                    imageVector = Icons.Default.Pending,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(Res.string.shell_pendingcount_pending, pendingCount),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(Res.string.shell_changes_waiting_to_sync),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(Res.string.shell_no_pending_changes),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun SyncCompleteSection() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CloudDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(Res.string.shell_all_synced),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(Res.string.shell_your_library_is_up_to),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun FailedOperationsSection(
    failedOperations: List<PendingOperationUi>,
    onRetryOperation: (String) -> Unit,
    onDismissOperation: (String) -> Unit,
    onRetryAll: () -> Unit,
    onDismissAll: () -> Unit,
) {
    // Section header with bulk actions
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${failedOperations.size} failed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row {
            TextButton(onClick = onRetryAll) {
                Text(stringResource(Res.string.shell_retry_all))
            }
            TextButton(onClick = onDismissAll) {
                Text(stringResource(Res.string.shell_dismiss_all))
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // List of failed operations
    @Suppress("MagicNumber") // Max 4 items visible, 64dp per item
    LazyColumn(
        modifier = Modifier.height((failedOperations.size.coerceAtMost(4) * 64).dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(failedOperations, key = { it.id }) { operation ->
            FailedOperationItem(
                operation = operation,
                onRetry = { onRetryOperation(operation.id) },
                onDismiss = { onDismissOperation(operation.id) },
            )
        }
    }
}

@Composable
private fun FailedOperationItem(
    operation: PendingOperationUi,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = operation.description,
                    style = MaterialTheme.typography.bodySmall,
                )
                operation.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }

            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(Res.string.common_retry),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(Res.string.common_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
