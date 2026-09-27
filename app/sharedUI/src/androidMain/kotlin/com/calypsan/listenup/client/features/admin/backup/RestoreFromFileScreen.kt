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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.util.DocumentPickerResult
import com.calypsan.listenup.client.util.rememberListenUpBackupPicker
import com.calypsan.listenup.core.BackupId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_restore_from_file
import listenup.composeapp.generated.resources.admin_restore_from_file_choose
import listenup.composeapp.generated.resources.admin_restore_from_file_description
import listenup.composeapp.generated.resources.admin_restore_from_file_detail
import listenup.composeapp.generated.resources.admin_restore_from_file_upload_failed
import listenup.composeapp.generated.resources.admin_restore_from_file_uploading
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreFromFileScreen(
    viewModel: RestoreFromFileViewModel = koinViewModel(),
    onBackClick: () -> Unit,
    onUploaded: (BackupId) -> Unit,
) {
    val haptics = LocalHaptics.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { id -> onUploaded(id) }
    }

    val picker =
        rememberListenUpBackupPicker { result ->
            if (result is DocumentPickerResult.Success) {
                viewModel.onFilePicked(result.fileSource)
            }
        }

    val canNavigateBack = state !is RestoreFromFileUiState.Uploading

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.admin_restore_from_file),
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
        RestoreFromFileContent(
            state = state,
            onChooseFile = { picker.launch() },
            onTryAgain = {
                viewModel.reset()
                picker.launch()
            },
            modifier = Modifier.padding(paddingValues),
        )
    }
}

/**
 * The restore-from-file body for each [RestoreFromFileUiState], hosted by its scaffold: the file
 * choice, the upload in flight, or the failure with a way to try again.
 */
@Composable
internal fun RestoreFromFileContent(
    state: RestoreFromFileUiState,
    onChooseFile: () -> Unit,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Every state here — the choice, its upload, a failed upload — is the flow's first step.
    FlowWithSteps(
        steps = restoreFlowSteps(),
        currentStep = RestoreFlowStep.CHOOSE,
        modifier = modifier,
    ) { isWide, paneModifier ->
        when (state) {
            is RestoreFromFileUiState.Idle -> {
                IdleUploadContent(onChooseFile = onChooseFile, isWide = isWide, modifier = paneModifier)
            }

            is RestoreFromFileUiState.Uploading -> {
                UploadingContent(filename = state.filename, modifier = paneModifier)
            }

            is RestoreFromFileUiState.Error -> {
                ErrorUploadContent(
                    message = state.error.localized(),
                    onTryAgain = onTryAgain,
                    isWide = isWide,
                    modifier = paneModifier,
                )
            }
        }
    }
}

@Composable
private fun IdleUploadContent(
    onChooseFile: () -> Unit,
    isWide: Boolean,
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
            text = stringResource(Res.string.admin_restore_from_file_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = ReadableMeasure),
        )
        Text(
            text = stringResource(Res.string.admin_restore_from_file_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = ReadableMeasure),
        )
        // A phone parks the action at the foot of the screen; the wide pane keeps it under the text.
        if (!isWide) Spacer(modifier = Modifier.weight(1f))
        ListenUpButton(
            onClick = onChooseFile,
            text = stringResource(Res.string.admin_restore_from_file_choose),
            leadingIcon = Icons.Outlined.FolderOpen,
            fillMaxWidth = !isWide,
            modifier = Modifier.flowActionWidth(isWide),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UploadingContent(
    filename: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScallopBadge(size = 120.dp, containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
                modifier = Modifier.size(54.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = stringResource(Res.string.admin_restore_from_file_uploading, filename),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(38.dp))
        LinearWavyProgressIndicator(modifier = Modifier.widthIn(max = ReadableMeasure).fillMaxWidth())
    }
}

@Composable
private fun ErrorUploadContent(
    message: String,
    onTryAgain: () -> Unit,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScallopBadge(size = 104.dp, containerColor = MaterialTheme.colorScheme.errorContainer) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.admin_restore_from_file_upload_failed),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.widthIn(max = ReadableMeasure).fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        ListenUpButton(
            onClick = onTryAgain,
            text = stringResource(Res.string.admin_restore_from_file_choose),
            leadingIcon = Icons.Outlined.FolderOpen,
            fillMaxWidth = !isWide,
            modifier = Modifier.flowActionWidth(isWide),
        )
    }
}
