package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.foundation.layout.widthIn
import com.calypsan.listenup.client.design.ReadableMeasure
import com.calypsan.listenup.client.design.components.FlowWithSteps
import com.calypsan.listenup.client.design.components.flowActionWidth
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
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
import com.calypsan.listenup.client.design.haptics.LocalHaptics
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
import com.calypsan.listenup.client.design.theme.Spacing
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
    val haptics = LocalHaptics.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    // Once the restore is in flight or done, the only way out is via the
    // Restoring spinner finishing or the Completed "Done" button. Hide back
    // navigation in those states to avoid abandoning a destructive operation.
    val canNavigateBack = state is RestoreBackupUiState.Idle

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.admin_restore_backup),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    if (canNavigateBack) {
                        IconButton(
                            onClick = {
                                haptics.press()
                                onBackClick()
                            },
                        ) {
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
        RestoreBackupContent(
            backupId = backupId,
            state = state,
            progress = progress,
            onRestoreClick = viewModel::requestRestore,
            onConfirmRestore = viewModel::confirmRestore,
            onCancelRestore = viewModel::cancelRestore,
            onDone = onComplete,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

/**
 * The restore screen's body for each [RestoreBackupUiState], hosted by its scaffold: the review with
 * its restore action, the confirmation over it, the live progress, and the result.
 *
 * The body forwards the ViewModel's three restore intents plus the exit; a parameter object would
 * only add an indirection layer Compose tooling discourages.
 */
@Suppress("LongParameterList")
@Composable
internal fun RestoreBackupContent(
    backupId: String,
    state: RestoreBackupUiState,
    progress: BackupEvent?,
    onRestoreClick: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowWithSteps(
        steps = restoreFlowSteps(),
        currentStep = state.flowStep(),
        modifier = modifier,
    ) { isWide, paneModifier ->
        when (state) {
            is RestoreBackupUiState.Idle -> {
                IdleContent(
                    backupId = backupId,
                    error = state.error,
                    onRestoreClick = onRestoreClick,
                    isWide = isWide,
                    modifier = paneModifier,
                )
            }

            RestoreBackupUiState.Confirming -> {
                // Keep the idle content beneath the confirmation dialog so the
                // screen never blanks while the user decides.
                IdleContent(
                    backupId = backupId,
                    error = null,
                    onRestoreClick = onRestoreClick,
                    isWide = isWide,
                    modifier = paneModifier,
                )
            }

            RestoreBackupUiState.Restoring -> {
                FullScreenLoadingIndicator(
                    message = restoreStatusLabel(progress),
                    modifier = paneModifier,
                )
            }

            is RestoreBackupUiState.Completed -> {
                CompletedContent(
                    result = state.result,
                    onDone = onDone,
                    isWide = isWide,
                    modifier = paneModifier,
                )
            }
        }
    }

    if (state == RestoreBackupUiState.Confirming) {
        ListenUpDestructiveDialog(
            onDismissRequest = onCancelRestore,
            title = "Restore Backup?",
            text =
                "This replaces everything on this server, including all user accounts, with the " +
                    "contents of this backup. You'll be signed out and must sign in again with an " +
                    "account from this backup. This cannot be undone.",
            confirmText = "Restore",
            onConfirm = onConfirmRestore,
            icon = Icons.Default.Warning,
        )
    }
}

/** Where this screen's state sits in the restore flow's steps. */
private fun RestoreBackupUiState.flowStep(): Int =
    when (this) {
        is RestoreBackupUiState.Idle, RestoreBackupUiState.Confirming -> RestoreFlowStep.REVIEW
        RestoreBackupUiState.Restoring -> RestoreFlowStep.RESTORE
        is RestoreBackupUiState.Completed -> RestoreFlowStep.SIGN_IN
    }

@Composable
private fun IdleContent(
    backupId: String,
    error: AppError?,
    onRestoreClick: () -> Unit,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.widthIn(max = ReadableMeasure).fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
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
            modifier = Modifier.widthIn(max = ReadableMeasure).fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
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

        error?.let { ErrorCard(text = it.localized(), modifier = Modifier.widthIn(max = ReadableMeasure)) }

        // A phone parks the action at the foot of the screen; the wide pane keeps it under the cards.
        if (!isWide) Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onRestoreClick,
            text = "Restore this backup",
            fillMaxWidth = !isWide,
            modifier = Modifier.flowActionWidth(isWide),
        )
    }
}

@Composable
private fun CompletedContent(
    result: RestoreResult,
    onDone: () -> Unit,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.widthIn(max = ReadableMeasure).fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(Spacing.lg)) {
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

        if (!isWide) Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onDone,
            text = "Done",
            fillMaxWidth = !isWide,
            modifier = Modifier.flowActionWidth(isWide),
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
        Box(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
