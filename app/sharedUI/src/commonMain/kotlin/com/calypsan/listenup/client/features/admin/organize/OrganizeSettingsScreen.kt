package com.calypsan.listenup.client.features.admin.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.calypsan.listenup.client.design.components.ListenUpButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.calypsan.listenup.client.design.components.ListenUpFab
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.presentation.admin.OrganizeRunProgress
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsEvent
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
import listenup.composeapp.generated.resources.admin_organize_already
import listenup.composeapp.generated.resources.admin_organize_confirm_renames
import listenup.composeapp.generated.resources.admin_organize_confirm_row
import listenup.composeapp.generated.resources.admin_organize_confirm_run
import listenup.composeapp.generated.resources.admin_organize_confirm_summary
import listenup.composeapp.generated.resources.admin_organize_confirm_title
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
import listenup.composeapp.generated.resources.admin_organize_run
import listenup.composeapp.generated.resources.admin_organize_saved
import listenup.composeapp.generated.resources.admin_organize_series_prefix
import listenup.composeapp.generated.resources.admin_organize_structure
import listenup.composeapp.generated.resources.admin_save_settings
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_ok
import org.jetbrains.compose.resources.stringResource

/** Content column width cap so the form reads well at medium/expanded window widths. */
private val ContentMaxWidth = 640.dp

/**
 * Bottom clearance under the scrolling form so the Organize Library button can always be scrolled
 * clear of the Save FAB. `Scaffold` reserves the bottom bar's height in its content padding, but
 * never the FAB's — without this the FAB sits on top of the button's trailing edge.
 */
private val FabClearance = 88.dp

/** Horizontal room the floating Save action needs beside the sweep button (FAB width + breathing space). */
private val FabInlineGutter = 72.dp

/**
 * Admin file-organizer settings screen (#850): the schema pickers, plus **two visibly distinct
 * actions**, because they are two different promises.
 *
 * The **Save FAB** persists the rules — live for future arrivals at once, and not one file moves;
 * a snackbar says so. The **Organize Library** button is the sweep: it fetches a server-side plan
 * preview, the consent dialog shows the full scope ("moves N files across M folders; K collisions
 * resolved") plus before→after rows, and only confirming persists AND relocates, with live
 * progress and a terminal report (Resume re-previews the remainder after a partial failure).
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

    // One-shot "rules saved — nothing moved" confirmation.
    val rulesSavedMessage = stringResource(Res.string.admin_organize_saved)
    val alreadyOrganizedMessage = stringResource(Res.string.admin_organize_already)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                OrganizeSettingsEvent.RulesSaved -> snackbarHostState.showSnackbar(rulesSavedMessage)
                OrganizeSettingsEvent.AlreadyOrganized -> snackbarHostState.showSnackbar(alreadyOrganizedMessage)
            }
        }
    }

    val ready = state as? OrganizeSettingsUiState.Ready
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
        floatingActionButton = {
            if (ready != null) {
                ListenUpFab(
                    onClick = viewModel::saveRules,
                    icon = Icons.Outlined.Save,
                    contentDescription = stringResource(Res.string.admin_save_settings),
                    enabled = !ready.isWorking,
                )
            }
        },
    ) { innerPadding ->
        when (val current = state) {
            is OrganizeSettingsUiState.Loading -> {
                FullScreenLoadingIndicator()
            }

            is OrganizeSettingsUiState.Error -> {
                Text(
                    text = current.error.localized(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(innerPadding).padding(24.dp),
                )
            }

            is OrganizeSettingsUiState.Ready -> {
                OrganizeSettingsContent(
                    state = current,
                    viewModel = viewModel,
                    innerPadding = innerPadding,
                )
                current.preview?.let { preview ->
                    OrganizeConfirmDialog(
                        preview = preview,
                        onConfirm = viewModel::confirmOrganize,
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
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = FabClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ContentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
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

            // A filled, full-width primary action rather than a trailing text link. This one
            // moves files on disk, and the affordance should carry the weight of what it starts —
            // as a right-aligned link it read as an afterthought and was missed entirely on first
            // use. "Organize Library", not "Apply": the rules are already applied by the Save FAB;
            // this is the sweep over books that already exist.
            ListenUpButton(
                text = stringResource(Res.string.admin_organize_run),
                onClick = viewModel::organize,
                enabled = !state.isWorking,
                isLoading = state.isWorking,
                // The Save FAB floats over the bottom-end corner, which is exactly where a
                // full-width button's end sits — on device it covered the button's right edge.
                // Yield that corner so the two peer actions sit side by side instead of stacked.
                modifier = Modifier.padding(end = FabInlineGutter),
            )
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

/**
 * The consent dialog: full scope counts + a browsable sample of before→after rows.
 *
 * The scope is two numbers, not one, because the plan holds two kinds of work. Folder relocations
 * get the summary line; books already in the right folder whose audio file is merely misnamed get
 * their own line. A plan of nothing but renames shows only the second — leading with
 * "Moves 0 files across 0 folders" would read as a no-op for work that is real.
 */
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
                if (preview.bookCount > 0) {
                    Text(
                        stringResource(
                            Res.string.admin_organize_confirm_summary,
                            preview.fileCount,
                            preview.bookCount,
                            preview.collisionCount,
                        ),
                    )
                }
                if (preview.renamedInPlaceCount > 0) {
                    Text(
                        stringResource(
                            Res.string.admin_organize_confirm_renames,
                            preview.renamedInPlaceCount,
                        ),
                    )
                }
                preview.entries.take(PREVIEW_ROWS_SHOWN).forEach { entry ->
                    // An in-place rename's folder is unchanged, so the filenames are the story;
                    // rendering its folder on both sides would show a change that isn't one.
                    val before = entry.renamedFrom ?: entry.fromPath.substringAfterLast('/')
                    val after = entry.renamedTo ?: entry.toPath
                    Text(
                        text = stringResource(Res.string.admin_organize_confirm_row, before, after),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val plannedBooks = preview.bookCount + preview.renamedInPlaceCount
                val remaining = plannedBooks - minOf(preview.entries.size, PREVIEW_ROWS_SHOWN)
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
