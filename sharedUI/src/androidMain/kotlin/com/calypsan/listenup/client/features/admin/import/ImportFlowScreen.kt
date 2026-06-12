package com.calypsan.listenup.client.features.admin.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.import.ImportAnalysis
import com.calypsan.listenup.api.dto.import.ImportResult
import com.calypsan.listenup.api.dto.import.MatchTier
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.presentation.admin.import.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.import.ImportFlowViewModel
import com.calypsan.listenup.client.util.DocumentPickerResult
import com.calypsan.listenup.client.util.rememberABSBackupPicker
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_try_again
import listenup.composeapp.generated.resources.import_apply_import
import listenup.composeapp.generated.resources.import_analyzing_subtitle
import listenup.composeapp.generated.resources.import_applying_subtitle
import listenup.composeapp.generated.resources.import_auto_matched
import listenup.composeapp.generated.resources.import_books_matched
import listenup.composeapp.generated.resources.import_books_skipped
import listenup.composeapp.generated.resources.import_choose_backup_file
import listenup.composeapp.generated.resources.import_done_subtitle
import listenup.composeapp.generated.resources.import_done_title
import listenup.composeapp.generated.resources.import_error_title
import listenup.composeapp.generated.resources.import_finish
import listenup.composeapp.generated.resources.import_idle_subtitle
import listenup.composeapp.generated.resources.import_idle_title
import listenup.composeapp.generated.resources.import_items_matched
import listenup.composeapp.generated.resources.import_matching_current_item
import listenup.composeapp.generated.resources.import_needs_attention
import listenup.composeapp.generated.resources.import_no_attention_needed
import listenup.composeapp.generated.resources.import_records_imported
import listenup.composeapp.generated.resources.import_sessions_imported
import listenup.composeapp.generated.resources.import_sessions_written
import listenup.composeapp.generated.resources.import_skip_item
import listenup.composeapp.generated.resources.import_title
import listenup.composeapp.generated.resources.import_uploading_subtitle
import listenup.composeapp.generated.resources.import_uploading_title
import listenup.composeapp.generated.resources.import_users_matched
import listenup.composeapp.generated.resources.import_writing_current_item
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Single-screen host for the entire ABS import flow. All state transitions are driven by
 * [ImportFlowUiState] from [ImportFlowViewModel]; no navigation occurs between phases.
 *
 * [Idle] hosts the OS file picker: picking a file immediately calls [ImportFlowViewModel.start].
 * Progress phases reuse the visual treatment from LibraryScanScreen (label + current item + counters).
 * [Review] surfaces unmatched / ambiguous items for admin attention with per-item skip toggles.
 * [Done] / [Error] carry action buttons that return to the back stack or reset the flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFlowScreen(
    onNavigateBack: () -> Unit,
    viewModel: ImportFlowViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The file picker is declared here (at the screen level) so the composable lifecycle
    // that wires the ActivityResult launcher is stable across all phases. It is only
    // *launched* from the Idle content when the admin taps "Choose backup file".
    val filePicker =
        rememberABSBackupPicker { result ->
            if (result is DocumentPickerResult.Success) {
                viewModel.start(result.fileSource)
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            when (val state = uiState) {
                is ImportFlowUiState.Idle -> {
                    IdleContent(onChooseFile = { filePicker.launch() })
                }

                is ImportFlowUiState.Uploading -> {
                    UploadingContent(state = state)
                }

                is ImportFlowUiState.Analyzing -> {
                    AnalyzingContent(state = state)
                }

                is ImportFlowUiState.Review -> {
                    ReviewContent(
                        state = state,
                        // setBookOverride(id, null) means "skip this item"; toggling again
                        // re-calls setBookOverride(id, null) which is idempotent — the admin
                        // can only skip, not un-skip in this function-first UI.
                        onBookSkipToggle = { absItemId ->
                            viewModel.setBookOverride(absItemId, null)
                        },
                        onConfirm = { viewModel.confirmAndApply() },
                    )
                }

                is ImportFlowUiState.Applying -> {
                    ApplyingContent(state = state)
                }

                is ImportFlowUiState.Done -> {
                    DoneContent(
                        result = state.result,
                        onFinish = {
                            viewModel.reset()
                            onNavigateBack()
                        },
                    )
                }

                is ImportFlowUiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onTryAgain = { viewModel.reset() },
                    )
                }
            }
        }
    }
}

// ─── Idle ─────────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(onChooseFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.import_idle_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.import_idle_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        ListenUpButton(
            text = stringResource(Res.string.import_choose_backup_file),
            onClick = onChooseFile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Uploading ────────────────────────────────────────────────────────────────

@Composable
private fun UploadingContent(state: ImportFlowUiState.Uploading) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(Res.string.import_uploading_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_uploading_subtitle, state.filename),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// ─── Analyzing ───────────────────────────────────────────────────────────────

@Composable
private fun AnalyzingContent(state: ImportFlowUiState.Analyzing) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(Res.string.import_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_analyzing_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.import_items_matched, state.done, state.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.currentItem?.let { item ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.import_matching_current_item, item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                text = stringResource(Res.string.import_users_matched, state.usersMatched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.import_books_matched, state.booksMatched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Review ───────────────────────────────────────────────────────────────────

@Composable
private fun ReviewContent(
    state: ImportFlowUiState.Review,
    onBookSkipToggle: (com.calypsan.listenup.core.AbsItemId) -> Unit,
    onConfirm: () -> Unit,
) {
    val analysis = state.analysis
    val attentionItems =
        analysis.ambiguous + analysis.unmatched
    val autoMatchedCount =
        analysis.bookMatchCounts
            .filterKeys { it != MatchTier.AMBIGUOUS && it != MatchTier.UNMATCHED }
            .values
            .sum()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Summary header
        item(key = "summary") {
            ReviewSummaryCard(
                analysis = analysis,
                autoMatchedCount = autoMatchedCount,
            )
        }

        // Needs-attention items
        if (attentionItems.isNotEmpty()) {
            item(key = "attention_header") {
                SectionLabel(stringResource(Res.string.import_needs_attention))
            }
            items(attentionItems, key = { "item_${it.absItemId.value}" }) { item ->
                val isSkipped = state.bookOverrides.containsKey(item.absItemId)
                NeedsAttentionCard(
                    title = item.title,
                    tier =
                        if (analysis.unmatched.any { it.absItemId == item.absItemId }) {
                            MatchTier.UNMATCHED
                        } else {
                            MatchTier.AMBIGUOUS
                        },
                    isSkipped = isSkipped,
                    onSkipToggle = { onBookSkipToggle(item.absItemId) },
                )
            }
        } else {
            item(key = "all_matched") {
                Text(
                    text = stringResource(Res.string.import_no_attention_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Auto-matched summary
        if (autoMatchedCount > 0) {
            item(key = "auto_header") {
                SectionLabel(
                    stringResource(
                        Res.string.import_books_matched,
                        autoMatchedCount.toString(),
                    ) + " — " + stringResource(Res.string.import_auto_matched),
                )
            }
        }

        // Apply button (sticky at bottom via last item)
        item(key = "apply_button") {
            Spacer(Modifier.height(8.dp))
            ListenUpButton(
                text = stringResource(Res.string.import_apply_import),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReviewSummaryCard(
    analysis: ImportAnalysis,
    autoMatchedCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.import_users_matched, analysis.userMatches.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(Res.string.import_books_matched, autoMatchedCount.toString()),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (analysis.ambiguous.isNotEmpty() || analysis.unmatched.isNotEmpty()) {
                val attentionCount = analysis.ambiguous.size + analysis.unmatched.size
                Text(
                    text =
                        stringResource(
                            Res.string.import_needs_attention,
                        ) + ": $attentionCount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NeedsAttentionCard(
    title: String,
    tier: MatchTier,
    isSkipped: Boolean,
    onSkipToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSkipped) {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    },
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = tier.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onSkipToggle,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text(
                    text = stringResource(Res.string.import_skip_item),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

// ─── Applying ────────────────────────────────────────────────────────────────

@Composable
private fun ApplyingContent(state: ImportFlowUiState.Applying) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(Res.string.import_apply_import),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_applying_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.import_items_matched, state.done, state.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.currentItem?.let { item ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.import_writing_current_item, item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.import_sessions_written, state.sessionsWritten),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Done ────────────────────────────────────────────────────────────────────

@Composable
private fun DoneContent(
    result: ImportResult,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.import_done_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.import_done_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.import_records_imported, result.importedCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.import_sessions_imported, result.sessionsImported),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.import_books_skipped, result.skippedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        ListenUpButton(
            text = stringResource(Res.string.import_finish),
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Error ───────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onTryAgain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.import_error_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        ListenUpButton(
            text = stringResource(Res.string.common_try_again),
            onClick = onTryAgain,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
