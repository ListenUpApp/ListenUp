package com.calypsan.listenup.client.features.admin.import

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.StatTile
import com.calypsan.listenup.client.design.components.StatTileTone
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_try_again
import listenup.composeapp.generated.resources.import_all_done
import listenup.composeapp.generated.resources.import_analyzing_subtitle
import listenup.composeapp.generated.resources.import_apply_import
import listenup.composeapp.generated.resources.import_choose_backup_file
import listenup.composeapp.generated.resources.import_currently_writing_label
import listenup.composeapp.generated.resources.import_data_stays_on_server
import listenup.composeapp.generated.resources.import_error_title
import listenup.composeapp.generated.resources.import_finish
import listenup.composeapp.generated.resources.import_flow_sessions_imported
import listenup.composeapp.generated.resources.import_idle_subtitle
import listenup.composeapp.generated.resources.import_items_written_label
import listenup.composeapp.generated.resources.import_keep_app_open
import listenup.composeapp.generated.resources.import_matching_current_item
import listenup.composeapp.generated.resources.import_review_books_section
import listenup.composeapp.generated.resources.import_review_users_section
import listenup.composeapp.generated.resources.import_skipped_books_subtitle
import listenup.composeapp.generated.resources.import_skipped_books_title
import listenup.composeapp.generated.resources.import_stat_records_imported
import listenup.composeapp.generated.resources.import_stat_sessions_imported
import listenup.composeapp.generated.resources.import_stat_sessions_written
import listenup.composeapp.generated.resources.import_stat_users_merged
import listenup.composeapp.generated.resources.import_uploading_subtitle
import listenup.composeapp.generated.resources.import_writing_current_item
import listenup.composeapp.generated.resources.import_writing_history_subtitle
import listenup.composeapp.generated.resources.import_writing_history_title
import org.jetbrains.compose.resources.stringResource

// ─── Idle (intro) ───────────────────────────────────────────────────────────────

@Composable
internal fun IdleContent(onChooseFile: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.import_idle_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                HowItWorksStep(
                    icon = ListenUpIcons.FolderOpen,
                    text = stringResource(Res.string.import_choose_backup_file),
                )
                Spacer(Modifier.height(16.dp))
                HowItWorksStep(
                    icon = ListenUpIcons.Group,
                    text = stringResource(Res.string.import_review_users_section),
                )
                Spacer(Modifier.height(16.dp))
                HowItWorksStep(icon = ListenUpIcons.AutoAwesome, text = stringResource(Res.string.import_apply_import))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ListenUpIcons.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(Res.string.import_data_stays_on_server),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ListenUpButton(
            text = stringResource(Res.string.import_choose_backup_file),
            onClick = onChooseFile,
            leadingIcon = ListenUpIcons.FolderOpen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HowItWorksStep(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─── Uploading ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun UploadingContent(state: ImportFlowUiState.Uploading) {
    ProgressMedallionContent(
        icon = ListenUpIcons.CloudUpload,
        subtitle = stringResource(Res.string.import_uploading_subtitle, state.filename),
    ) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// ─── Analyzing ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AnalyzingContent(state: ImportFlowUiState.Analyzing) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScallopMedallion(icon = ListenUpIcons.AutoAwesome, size = 64.dp, iconSize = 30.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(Res.string.import_analyzing_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(34.dp))
        if (state.total > 0) {
            PercentCountRow(done = state.done, total = state.total)
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.currentItem?.let { item ->
            Spacer(Modifier.height(14.dp))
            DetailChip(label = stringResource(Res.string.import_matching_current_item, item))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(
                value = state.usersMatched.toString(),
                label = stringResource(Res.string.import_stat_users_merged),
                icon = ListenUpIcons.Group,
                colors = StatTileTone.primary(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = state.booksMatched.toString(),
                label = stringResource(Res.string.import_review_books_section),
                icon = ListenUpIcons.LibraryBooks,
                colors = StatTileTone.tertiary(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ─── Applying ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ApplyingContent(state: ImportFlowUiState.Applying) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScallopMedallion(icon = ListenUpIcons.Storage, size = 64.dp, iconSize = 30.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(Res.string.import_writing_history_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.import_writing_history_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(34.dp))
        if (state.total > 0) {
            PercentCountRow(done = state.done, total = state.total)
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(
                progress = { state.done.toFloat() / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        MutedLine(stringResource(Res.string.import_items_written_label))

        state.currentItem?.let { item ->
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Text(
                        text = stringResource(Res.string.import_currently_writing_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    DetailChip(label = stringResource(Res.string.import_writing_current_item, item))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        StatTile(
            value = state.sessionsWritten.toString(),
            label = stringResource(Res.string.import_stat_sessions_written),
            icon = ListenUpIcons.GraphicEq,
            colors = StatTileTone.primary(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))
        FooterNote(stringResource(Res.string.import_keep_app_open))
    }
}

// ─── Done (complete) ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DoneContent(
    result: ImportResult,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ScallopBadge(size = 104.dp, containerColor = MaterialTheme.colorScheme.primary) {
            Icon(
                imageVector = ListenUpIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.import_all_done),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.import_flow_sessions_imported, result.sessionsImported.toString()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(26.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                value = result.importedCount.toString(),
                label = stringResource(Res.string.import_stat_records_imported),
                icon = ListenUpIcons.Inventory2,
                colors = StatTileTone.primary(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = result.sessionsImported.toString(),
                label = stringResource(Res.string.import_stat_sessions_imported),
                icon = ListenUpIcons.GraphicEq,
                colors = StatTileTone.tertiary(),
                modifier = Modifier.weight(1f),
            )
        }
        // Only surfaced when a mapped user genuinely has history for books that aren't in this
        // library — the honest "couldn't import" count. A clean import shows no scary skip box.
        if (result.booksNotInLibrary > 0) {
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(modifier = Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ListenUpIcons.SkipNext,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.import_skipped_books_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.import_skipped_books_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = result.booksNotInLibrary.toString(),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ListenUpButton(
            text = stringResource(Res.string.import_finish),
            onClick = onFinish,
            leadingIcon = ListenUpIcons.Check,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Error ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ErrorContent(
    message: String,
    onTryAgain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScallopBadge(size = 104.dp, containerColor = MaterialTheme.colorScheme.errorContainer) {
            Icon(
                imageVector = ListenUpIcons.Close,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.import_error_title),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontWeight = FontWeight.ExtraBold,
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
