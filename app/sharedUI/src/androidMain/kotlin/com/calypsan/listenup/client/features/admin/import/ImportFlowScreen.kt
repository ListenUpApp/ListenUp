package com.calypsan.listenup.client.features.admin.import

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.WizardStepTracker
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.util.DocumentPickerResult
import com.calypsan.listenup.client.util.rememberABSBackupPicker
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.import_apply_import
import listenup.composeapp.generated.resources.import_done_title
import listenup.composeapp.generated.resources.import_error_title
import listenup.composeapp.generated.resources.import_flow_eyebrow
import listenup.composeapp.generated.resources.import_flow_step_apply
import listenup.composeapp.generated.resources.import_flow_step_done
import listenup.composeapp.generated.resources.import_flow_step_review
import listenup.composeapp.generated.resources.import_flow_step_upload
import listenup.composeapp.generated.resources.import_flow_title
import listenup.composeapp.generated.resources.import_idle_title
import listenup.composeapp.generated.resources.import_review_users_section
import listenup.composeapp.generated.resources.import_uploading_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val CONTENT_MAX_WIDTH = 560.dp

// Zero-based wizard step index for each progress phase, mapped onto the 4-step tracker.
private const val STEP_UPLOAD = 0
private const val STEP_REVIEW = 1
private const val STEP_APPLY = 2
private const val STEP_DONE = 3

/**
 * Single-screen host for the entire ABS import flow. All state transitions are driven by
 * [ImportFlowUiState] from [ImportFlowViewModel]; no navigation occurs between phases.
 *
 * The flow wears the M3 Expressive wizard chrome — a colour-blocked [ColorBlockHero] hosting a
 * [WizardStepTracker] (Upload → Review → Apply → Done) — over expressive per-phase content:
 * scallop medallions, the wavy progress indicator, tonal stat tiles, and status-toned review cards.
 *
 * [Idle] hosts the OS file picker: picking a file immediately calls [ImportFlowViewModel.start].
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

    ListenUpScaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            FlowHero(state = uiState, onBack = onNavigateBack)
            CenteredColumn {
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
                            onSetUserMapping = { absUserId, userId ->
                                viewModel.setUserMapping(absUserId, userId)
                            },
                            onSkipUser = { viewModel.skipUser(it) },
                            onOpenBookSearch = { viewModel.openBookSearch(it) },
                            onCloseBookSearch = { viewModel.closeBookSearch() },
                            onBookSearchQueryChange = { viewModel.updateBookSearchQuery(it) },
                            onSelectBook = { absItemId, bookId -> viewModel.selectBook(absItemId, bookId) },
                            onSkipBook = { viewModel.skipBook(it) },
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
                            message = state.error.localized(),
                            onTryAgain = { viewModel.reset() },
                        )
                    }
                }
            }
        }
    }
}

// ─── Wizard chrome ─────────────────────────────────────────────────────────────

@Composable
private fun FlowHero(
    state: ImportFlowUiState,
    onBack: () -> Unit,
) {
    val title =
        when (state) {
            is ImportFlowUiState.Idle -> stringResource(Res.string.import_idle_title)
            is ImportFlowUiState.Uploading -> stringResource(Res.string.import_uploading_title)
            is ImportFlowUiState.Analyzing -> stringResource(Res.string.import_flow_title)
            is ImportFlowUiState.Review -> stringResource(Res.string.import_review_users_section)
            is ImportFlowUiState.Applying -> stringResource(Res.string.import_apply_import)
            is ImportFlowUiState.Done -> stringResource(Res.string.import_done_title)
            is ImportFlowUiState.Error -> stringResource(Res.string.import_error_title)
        }
    val step =
        when (state) {
            is ImportFlowUiState.Uploading, is ImportFlowUiState.Analyzing -> STEP_UPLOAD
            is ImportFlowUiState.Review -> STEP_REVIEW
            is ImportFlowUiState.Applying -> STEP_APPLY
            is ImportFlowUiState.Done -> STEP_DONE
            is ImportFlowUiState.Idle, is ImportFlowUiState.Error -> null
        }
    val steps =
        listOf(
            stringResource(Res.string.import_flow_step_upload),
            stringResource(Res.string.import_flow_step_review),
            stringResource(Res.string.import_flow_step_apply),
            stringResource(Res.string.import_flow_step_done),
        )
    ColorBlockHero(
        title = title,
        badgeIcon = ListenUpIcons.CloudUpload,
        onBack = onBack,
        modifier = Modifier.fillMaxWidth(),
        overline = stringResource(Res.string.import_flow_eyebrow),
        content =
            step?.let { currentStep ->
                {
                    WizardStepTracker(
                        steps = steps,
                        currentStep = currentStep,
                        accent = MaterialTheme.colorScheme.primary,
                        ink = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }
            },
    )
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            content()
        }
    }
}
