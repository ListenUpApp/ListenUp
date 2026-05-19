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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import com.calypsan.listenup.client.presentation.admin.MergeStrategy
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreBackupViewModel
import com.calypsan.listenup.client.presentation.admin.RestoreMode
import com.calypsan.listenup.client.presentation.admin.RestoreStep
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

    val readyState = state as? RestoreBackupUiState.Ready
    val currentStep = readyState?.step ?: RestoreStep.MODE_SELECTION

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(getStepTitle(currentStep)) },
                navigationIcon = {
                    if (readyState != null &&
                        currentStep != RestoreStep.RESTORING &&
                        currentStep != RestoreStep.RESULTS
                    ) {
                        IconButton(onClick = {
                            if (currentStep == RestoreStep.MODE_SELECTION) {
                                onBackClick()
                            } else {
                                viewModel.previousStep()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val s = state) {
            is RestoreBackupUiState.Loading -> {
                FullScreenLoadingIndicator(modifier = Modifier.padding(paddingValues))
            }

            is RestoreBackupUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is RestoreBackupUiState.Ready -> {
                RestoreBackupReadyContent(
                    state = s,
                    viewModel = viewModel,
                    onComplete = onComplete,
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }
}

@Composable
private fun RestoreBackupReadyContent(
    state: RestoreBackupUiState.Ready,
    viewModel: RestoreBackupViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.step) {
        RestoreStep.MODE_SELECTION -> {
            ModeSelectionContent(
                state = state,
                onModeSelected = viewModel::selectMode,
                onNext = viewModel::nextStep,
                modifier = modifier,
            )
        }

        RestoreStep.MERGE_STRATEGY -> {
            MergeStrategyContent(
                state = state,
                onStrategySelected = viewModel::selectMergeStrategy,
                onNext = viewModel::nextStep,
                modifier = modifier,
            )
        }

        RestoreStep.VALIDATION -> {
            ValidationContent(
                state = state,
                onPerformDryRun = viewModel::performDryRun,
                onNext = viewModel::nextStep,
                modifier = modifier,
            )
        }

        RestoreStep.CONFIRMATION -> {
            ConfirmationContent(
                state = state,
                onConfirm = viewModel::nextStep,
                onBack = viewModel::previousStep,
                modifier = modifier,
            )
        }

        RestoreStep.RESTORING -> {
            RestoringContent(
                modifier = modifier,
            )
        }

        RestoreStep.RESULTS -> {
            ResultsContent(
                state = state,
                onDone = onComplete,
                modifier = modifier,
            )
        }
    }
}

private fun getStepTitle(step: RestoreStep): String =
    when (step) {
        RestoreStep.MODE_SELECTION -> "Restore Mode"
        RestoreStep.MERGE_STRATEGY -> "Merge Strategy"
        RestoreStep.VALIDATION -> "Preview Changes"
        RestoreStep.CONFIRMATION -> "Confirm Restore"
        RestoreStep.RESTORING -> "Restoring..."
        RestoreStep.RESULTS -> "Restore Complete"
    }

@Composable
private fun ModeSelectionContent(
    state: RestoreBackupUiState.Ready,
    onModeSelected: (RestoreMode) -> Unit,
    onNext: () -> Unit,
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
        Text(
            text = "Choose how to restore from this backup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Validation info
        state.validation?.let { validation ->
            if (validation.valid) {
                val summary = validation.entityCounts.entries.joinToString { "${it.value} ${it.key}" }
                InfoCard(text = "Backup contains: $summary")
            } else {
                ErrorCard(
                    text = "Backup validation failed: ${validation.errors.firstOrNull() ?: "Unknown error"}",
                )
            }
        }

        if (state.isValidating) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ListenUpLoadingIndicator()
            }
        }

        Column(modifier = Modifier.selectableGroup()) {
            ModeOption(
                mode = RestoreMode.MERGE,
                icon = Icons.AutoMirrored.Outlined.MergeType,
                selected = state.mode == RestoreMode.MERGE,
                onSelect = { onModeSelected(RestoreMode.MERGE) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            ModeOption(
                mode = RestoreMode.FRESH,
                icon = Icons.Outlined.DeleteForever,
                selected = state.mode == RestoreMode.FRESH,
                onSelect = { onModeSelected(RestoreMode.FRESH) },
                isDestructive = true,
            )
        }

        // Warning for fresh restore
        if (state.mode == RestoreMode.FRESH) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text =
                            "This will permanently delete all existing data including " +
                                "users, books, listening history, and collections.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Error display
        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onNext,
            text = "Continue",
            enabled = state.mode != null && state.validation?.valid == true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Reserve space for mini player
    }
}

@Composable
private fun ModeOption(
    mode: RestoreMode,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    isDestructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onSelect,
                    role = Role.RadioButton,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        if (isDestructive) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        }
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Icon(
                icon,
                contentDescription = null,
                tint =
                    if (isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MergeStrategyContent(
    state: RestoreBackupUiState.Ready,
    onStrategySelected: (MergeStrategy) -> Unit,
    onNext: () -> Unit,
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
        Text(
            text = "How should conflicts be handled when an item exists in both the backup and your current data?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(modifier = Modifier.selectableGroup()) {
            MergeStrategy.entries.forEachIndexed { index, strategy ->
                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                StrategyOption(
                    strategy = strategy,
                    selected = state.mergeStrategy == strategy,
                    onSelect = { onStrategySelected(strategy) },
                )
            }
        }

        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onNext,
            text = "Continue",
            enabled = state.mergeStrategy != null,
            modifier = Modifier.fillMaxWidth(),
        )

        // Reserve space for mini player
    }
}

@Composable
private fun StrategyOption(
    strategy: MergeStrategy,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onSelect,
                    role = Role.RadioButton,
                ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strategy.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = strategy.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("LongMethod", "CognitiveComplexMethod")
@Composable
private fun ValidationContent(
    state: RestoreBackupUiState.Ready,
    onPerformDryRun: () -> Unit,
    onNext: () -> Unit,
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
        Text(
            text = "Preview what will happen during the restore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Summary of selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Restore Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text = "Mode: ${state.mode?.displayName ?: "Not selected"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.mode == RestoreMode.MERGE) {
                    Text(
                        text = "Strategy: ${state.mergeStrategy?.displayName ?: "Not selected"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (state.isValidating) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ListenUpLoadingIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Running preview...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            state.dryRunResults?.let { results ->
                // Show dry run results
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Preview Results",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        if (results.willImport.isNotEmpty()) {
                            Text(
                                text = "Will import:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            results.willImport.forEach { (type, count) ->
                                Text(
                                    text = "  $count $type",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        if (results.willSkip.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Will skip:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            results.willSkip.forEach { (type, count) ->
                                Text(
                                    text = "  $count $type",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        if (results.errors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${results.errors.size} potential issues found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } ?: Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPerformDryRun,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Run Preview")
                }
                TextButton(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip Preview")
                }
            }
        }

        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (state.dryRunResults != null) {
            ListenUpButton(
                onClick = onNext,
                text = "Continue to Confirmation",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Reserve space for mini player
    }
}

@Composable
private fun ConfirmationContent(
    state: RestoreBackupUiState.Ready,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
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
        Text(
            text = "Please confirm you want to proceed with the restore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.mode == RestoreMode.FRESH) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Full Data Wipe",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "All existing data will be permanently deleted. This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Summary",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                state.dryRunResults?.let { results ->
                    results.willImport.forEach { (type, count) ->
                        Text(
                            text = "$count $type will be imported",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            ListenUpButton(
                onClick = onConfirm,
                text = if (state.mode == RestoreMode.FRESH) "Confirm & Wipe" else "Restore",
                modifier = Modifier.weight(1f),
            )
        }

        // Reserve space for mini player
    }
}

@Composable
private fun RestoringContent(modifier: Modifier = Modifier) {
    FullScreenLoadingIndicator(message = "Restoring Backup...", modifier = modifier)
}

@Suppress("LongMethod", "CognitiveComplexMethod")
@Composable
private fun ResultsContent(
    state: RestoreBackupUiState.Ready,
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
        val results = state.restoreResults
        val hasErrors = results?.errors?.isNotEmpty() == true

        // Success/Error header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (hasErrors) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (hasErrors) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint =
                        if (hasErrors) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                )
                Column {
                    Text(
                        text = if (hasErrors) "Restore Completed with Issues" else "Restore Successful",
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (hasErrors) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                    )
                    results?.duration?.let {
                        Text(
                            text = "Completed in $it",
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (hasErrors) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                        )
                    }
                }
            }
        }

        // Import summary
        results?.let { r ->
            if (r.imported.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Imported",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        r.imported.forEach { (type, count) ->
                            Text(
                                text = "$count $type",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            if (r.skipped.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Skipped",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        r.skipped.forEach { (type, count) ->
                            Text(
                                text = "$count $type",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            if (r.errors.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Errors",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        r.errors.take(5).forEach { error ->
                            Text(
                                text = "${error.entityType}: ${error.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (r.errors.size > 5) {
                            Text(
                                text = "...and ${r.errors.size - 5} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            ErrorCard(text = error)
        }

        Spacer(modifier = Modifier.weight(1f))

        ListenUpButton(
            onClick = onDone,
            text = "Done",
            modifier = Modifier.fillMaxWidth(),
        )

        // Reserve space for mini player
    }
}

@Composable
private fun InfoCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp),
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
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}
