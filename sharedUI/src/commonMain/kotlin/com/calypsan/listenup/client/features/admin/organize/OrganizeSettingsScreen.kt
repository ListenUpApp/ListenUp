package com.calypsan.listenup.client.features.admin.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.organize.OrganizeAuthorForm
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizePreviewDto
import com.calypsan.listenup.api.dto.organize.OrganizeSeriesPrefix
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.presentation.admin.OrganizeRunProgress
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_organize
import listenup.composeapp.generated.resources.admin_organize_author_first_last
import listenup.composeapp.generated.resources.admin_organize_author_form
import listenup.composeapp.generated.resources.admin_organize_author_last_first
import listenup.composeapp.generated.resources.admin_organize_confirm_more_rows
import listenup.composeapp.generated.resources.admin_organize_confirm_run
import listenup.composeapp.generated.resources.admin_organize_confirm_summary
import listenup.composeapp.generated.resources.admin_organize_confirm_title
import listenup.composeapp.generated.resources.admin_organize_enable_subtitle
import listenup.composeapp.generated.resources.admin_organize_enable_title
import listenup.composeapp.generated.resources.admin_organize_prefix_book_n_dash
import listenup.composeapp.generated.resources.admin_organize_prefix_bracket_n
import listenup.composeapp.generated.resources.admin_organize_prefix_n_dash
import listenup.composeapp.generated.resources.admin_organize_prefix_none
import listenup.composeapp.generated.resources.admin_organize_preset_author_series_title
import listenup.composeapp.generated.resources.admin_organize_preset_author_title
import listenup.composeapp.generated.resources.admin_organize_preset_flat_title
import listenup.composeapp.generated.resources.admin_organize_progress_count
import listenup.composeapp.generated.resources.admin_organize_progress_title
import listenup.composeapp.generated.resources.admin_organize_report_done
import listenup.composeapp.generated.resources.admin_organize_report_resume
import listenup.composeapp.generated.resources.admin_organize_report_summary
import listenup.composeapp.generated.resources.admin_organize_series_prefix
import listenup.composeapp.generated.resources.admin_organize_structure
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_ok
import listenup.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource

/** Content column width cap so the form reads well at medium/expanded window widths. */
private val ContentMaxWidth = 640.dp

/**
 * Admin file-organizer settings screen (#850): enable toggle, schema pickers, and the
 * save-moment flow — Save fetches a server-side plan preview, the consent dialog shows the full
 * scope ("moves N files across M folders; K collisions resolved") plus before→after rows, and
 * confirming persists the settings AND runs the reorganization immediately, with live progress
 * and a terminal report (Resume re-fires the save after a partial failure).
 */
@Composable
fun OrganizeSettingsScreen(
    viewModel: OrganizeSettingsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val readyError = (state as? OrganizeSettingsUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it.localizedString())
            viewModel.clearError()
        }
    }

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.admin_organize),
                badgeIcon = Icons.Outlined.DriveFileMove,
                onBack = onBackClick,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val current = state) {
            is OrganizeSettingsUiState.Loading -> FullScreenLoadingIndicator()

            is OrganizeSettingsUiState.Error ->
                Text(
                    text = current.error.localized(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(innerPadding).padding(24.dp),
                )

            is OrganizeSettingsUiState.Ready -> {
                OrganizeSettingsContent(
                    state = current,
                    viewModel = viewModel,
                    innerPadding = innerPadding,
                )
                current.preview?.let { preview ->
                    OrganizeConfirmDialog(
                        preview = preview,
                        onConfirm = viewModel::confirmSave,
                        onDismiss = viewModel::dismissPreview,
                    )
                }
                current.run?.let { run ->
                    OrganizeRunDialog(
                        run = run,
                        onResume = viewModel::resumeAfterFailure,
                        onDismiss = viewModel::dismissRunReport,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrganizeSettingsContent(
    state: OrganizeSettingsUiState.Ready,
    viewModel: OrganizeSettingsViewModel,
    innerPadding: PaddingValues,
) {
    val settings = state.settings
    Column(
        modifier =
            Modifier
                .padding(innerPadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ContentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingRow(
                title = stringResource(Res.string.admin_organize_enable_title),
                subtitle = stringResource(Res.string.admin_organize_enable_subtitle),
                icon = Icons.Outlined.DriveFileMove,
            ) {
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    enabled = !state.isWorking,
                )
            }

            SectionGroup(
                label = stringResource(Res.string.admin_organize_structure),
                icon = Icons.Outlined.Folder,
            ) {
                RadioRow(
                    label = stringResource(Res.string.admin_organize_preset_author_series_title),
                    selected = settings.preset == OrganizePreset.AUTHOR_SERIES_TITLE,
                ) { viewModel.setPreset(OrganizePreset.AUTHOR_SERIES_TITLE) }
                RadioRow(
                    label = stringResource(Res.string.admin_organize_preset_author_title),
                    selected = settings.preset == OrganizePreset.AUTHOR_TITLE,
                ) { viewModel.setPreset(OrganizePreset.AUTHOR_TITLE) }
                RadioRow(
                    label = stringResource(Res.string.admin_organize_preset_flat_title),
                    selected = settings.preset == OrganizePreset.FLAT_TITLE,
                ) { viewModel.setPreset(OrganizePreset.FLAT_TITLE) }
            }

            if (settings.preset == OrganizePreset.AUTHOR_SERIES_TITLE) {
                SectionGroup(
                    label = stringResource(Res.string.admin_organize_series_prefix),
                    icon = Icons.Outlined.Tag,
                ) {
                    RadioRow(
                        label = stringResource(Res.string.admin_organize_prefix_book_n_dash),
                        selected = settings.seriesPrefix == OrganizeSeriesPrefix.BOOK_N_DASH,
                    ) { viewModel.setSeriesPrefix(OrganizeSeriesPrefix.BOOK_N_DASH) }
                    RadioRow(
                        label = stringResource(Res.string.admin_organize_prefix_n_dash),
                        selected = settings.seriesPrefix == OrganizeSeriesPrefix.N_DASH,
                    ) { viewModel.setSeriesPrefix(OrganizeSeriesPrefix.N_DASH) }
                    RadioRow(
                        label = stringResource(Res.string.admin_organize_prefix_bracket_n),
                        selected = settings.seriesPrefix == OrganizeSeriesPrefix.BRACKET_N,
                    ) { viewModel.setSeriesPrefix(OrganizeSeriesPrefix.BRACKET_N) }
                    RadioRow(
                        label = stringResource(Res.string.admin_organize_prefix_none),
                        selected = settings.seriesPrefix == OrganizeSeriesPrefix.NONE,
                    ) { viewModel.setSeriesPrefix(OrganizeSeriesPrefix.NONE) }
                }
            }

            if (settings.preset != OrganizePreset.FLAT_TITLE) {
                SectionGroup(
                    label = stringResource(Res.string.admin_organize_author_form),
                    icon = Icons.Outlined.Person,
                ) {
                    RadioRow(
                        label = stringResource(Res.string.admin_organize_author_first_last),
                        selected = settings.authorForm == OrganizeAuthorForm.FIRST_LAST,
                    ) { viewModel.setAuthorForm(OrganizeAuthorForm.FIRST_LAST) }
                    RadioRow(
                        label = stringResource(Res.string.admin_organize_author_last_first),
                        selected = settings.authorForm == OrganizeAuthorForm.LAST_FIRST,
                    ) { viewModel.setAuthorForm(OrganizeAuthorForm.LAST_FIRST) }
                }
            }

            TextButton(
                onClick = viewModel::save,
                enabled = !state.isWorking,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(Res.string.common_save))
            }
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The consent dialog: full scope counts + a browsable sample of before→after rows. */
@Composable
private fun OrganizeConfirmDialog(
    preview: OrganizePreviewDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.admin_organize_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        Res.string.admin_organize_confirm_summary,
                        preview.fileCount,
                        preview.bookCount,
                        preview.collisionCount,
                    ),
                )
                preview.entries.take(PREVIEW_ROWS_SHOWN).forEach { entry ->
                    Text(
                        text = "${entry.fromPath.substringAfterLast('/')} → ${entry.toPath}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val remaining = preview.bookCount - minOf(preview.entries.size, PREVIEW_ROWS_SHOWN)
                if (remaining > 0) {
                    Text(
                        text = stringResource(Res.string.admin_organize_confirm_more_rows, remaining),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.admin_organize_confirm_run)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** Run progress while in flight; terminal report (with Resume on partial failure) once done. */
@Composable
private fun OrganizeRunDialog(
    run: OrganizeRunProgress,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (run.terminal) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (run.terminal) Res.string.admin_organize_report_done else Res.string.admin_organize_progress_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (run.terminal) {
                    Text(stringResource(Res.string.admin_organize_report_summary, run.movedBooks, run.failedBooks))
                } else {
                    Text(stringResource(Res.string.admin_organize_progress_count, run.completed, run.total))
                    LinearProgressIndicator(
                        progress = { if (run.total > 0) run.completed.toFloat() / run.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (run.terminal) {
                if (run.hasFailures) {
                    TextButton(onClick = onResume) { Text(stringResource(Res.string.admin_organize_report_resume)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_ok)) }
            }
        },
    )
}

/** How many before→after rows the consent dialog lists before collapsing to "…and N more". */
private const val PREVIEW_ROWS_SHOWN = 8
