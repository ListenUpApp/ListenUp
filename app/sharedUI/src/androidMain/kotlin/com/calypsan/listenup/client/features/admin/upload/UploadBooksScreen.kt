package com.calypsan.listenup.client.features.admin.upload

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.uploads.UploadLimits
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksViewModel
import com.calypsan.listenup.client.features.bookdetail.formatFileSize
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.util.rememberUploadFilePicker
import com.calypsan.listenup.client.util.rememberUploadFolderPicker
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_upload_books
import listenup.composeapp.generated.resources.admin_upload_books_cancel
import listenup.composeapp.generated.resources.admin_upload_books_choose_files
import listenup.composeapp.generated.resources.admin_upload_books_choose_folder
import listenup.composeapp.generated.resources.admin_upload_books_description
import listenup.composeapp.generated.resources.admin_upload_books_done
import listenup.composeapp.generated.resources.admin_upload_books_duplicates
import listenup.composeapp.generated.resources.admin_upload_books_failed_title
import listenup.composeapp.generated.resources.admin_upload_books_failures
import listenup.composeapp.generated.resources.admin_upload_books_file_progress
import listenup.composeapp.generated.resources.admin_upload_books_file_too_large_body
import listenup.composeapp.generated.resources.admin_upload_books_file_too_large_title
import listenup.composeapp.generated.resources.admin_upload_books_finalizing
import listenup.composeapp.generated.resources.admin_upload_books_finished_title
import listenup.composeapp.generated.resources.admin_upload_books_imported
import listenup.composeapp.generated.resources.admin_upload_books_nothing_found
import listenup.composeapp.generated.resources.admin_upload_books_too_large_body
import listenup.composeapp.generated.resources.admin_upload_books_too_large_title
import listenup.composeapp.generated.resources.admin_upload_books_too_many_files_body
import listenup.composeapp.generated.resources.admin_upload_books_too_many_files_title
import listenup.composeapp.generated.resources.admin_upload_books_uploading
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_ok
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Why a selection was refused before a single byte went out.
 *
 * Checking client-side is a courtesy — the server enforces every one of these per request — but
 * discovering a 70 GiB selection is over the cap after uploading 60 of them is the difference
 * between a dialog and a wasted evening.
 */
private sealed interface SelectionRefusal {
    data class TooManyFiles(
        val count: Int,
    ) : SelectionRefusal

    data class TooLarge(
        val bytes: Long,
    ) : SelectionRefusal

    data class FileTooLarge(
        val filename: String,
        val bytes: Long,
    ) : SelectionRefusal
}

/** The first cap [candidates] breaks, or null when the selection is within every one of them. */
private fun refusalFor(candidates: List<UploadCandidate>): SelectionRefusal? {
    if (candidates.size > UploadLimits.MAX_FILES) return SelectionRefusal.TooManyFiles(candidates.size)
    candidates.firstOrNull { (it.source.size ?: 0L) > UploadLimits.MAX_FILE_BYTES }?.let {
        return SelectionRefusal.FileTooLarge(it.source.filename, it.source.size ?: 0L)
    }
    val total = candidates.sumOf { it.source.size ?: 0L }
    return if (total > UploadLimits.MAX_SESSION_BYTES) SelectionRefusal.TooLarge(total) else null
}

/**
 * Upload books into the library from this device.
 *
 * There is deliberately no "is this one book or three?" step. The picker sends the structure the
 * user chose — a folder keeps its shape, loose files arrive flat — and the server's grouper, which
 * can read the audio tags, decides what the books are. Asking the user to answer a question the
 * server can answer better would just create a second opinion to reconcile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadBooksScreen(
    viewModel: UploadBooksViewModel = koinViewModel(),
    onBackClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var refusal by remember { mutableStateOf<SelectionRefusal?>(null) }
    var emptySelection by remember { mutableStateOf(false) }

    fun offer(candidates: List<UploadCandidate>) {
        if (candidates.isEmpty()) {
            emptySelection = true
            return
        }
        when (val refused = refusalFor(candidates)) {
            null -> viewModel.onFilesPicked(candidates)
            else -> refusal = refused
        }
    }

    val pickFolder = rememberUploadFolderPicker(::offer)
    val pickFiles = rememberUploadFilePicker(::offer)

    val busy = state is UploadBooksUiState.Uploading || state is UploadBooksUiState.Finalizing

    // Hiding the toolbar arrow while busy stops one way out; the back GESTURE is the other, and
    // it fires from an edge touch as easily as from intent. Without this, forty minutes into an
    // upload a reflexive swipe pops the entry, clears the ViewModel, cancels the collector and
    // abandons the session — every staged byte gone, with no confirmation and no notice. Swallow
    // it: Cancel is the deliberate way to stop, and it says what it does.
    PlatformBackHandler(enabled = busy) { /* deliberately inert while a transfer is in flight */ }

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.admin_upload_books)) },
                navigationIcon = {
                    if (!busy) {
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
    ) { padding ->
        when (val s = state) {
            is UploadBooksUiState.Idle -> {
                IdleContent(
                    onChooseFolder = pickFolder,
                    onChooseFiles = pickFiles,
                    modifier = Modifier.padding(padding),
                )
            }

            is UploadBooksUiState.Uploading -> {
                UploadingContent(
                    state = s,
                    onCancel = viewModel::cancel,
                    modifier = Modifier.padding(padding),
                )
            }

            is UploadBooksUiState.Finalizing -> {
                FinalizingContent(modifier = Modifier.padding(padding))
            }

            is UploadBooksUiState.Finished -> {
                FinishedContent(
                    state = s,
                    onDone = {
                        viewModel.reset()
                        onBackClick()
                    },
                    modifier = Modifier.padding(padding),
                )
            }

            is UploadBooksUiState.Error -> {
                FailedContent(
                    message = s.error.localized(),
                    onTryAgain = {
                        viewModel.reset()
                        pickFolder()
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    refusal?.let { refused ->
        ListenUpAlertDialog(
            onDismissRequest = { refusal = null },
            title = stringResource(refused.titleRes()),
            text = refused.body(),
            confirmText = stringResource(Res.string.common_ok),
            onConfirm = { refusal = null },
            dismissText = null,
        )
    }

    if (emptySelection) {
        ListenUpAlertDialog(
            onDismissRequest = { emptySelection = false },
            title = stringResource(Res.string.admin_upload_books),
            text = stringResource(Res.string.admin_upload_books_nothing_found),
            confirmText = stringResource(Res.string.common_ok),
            onConfirm = { emptySelection = false },
            dismissText = null,
        )
    }
}

private fun SelectionRefusal.titleRes() =
    when (this) {
        is SelectionRefusal.TooManyFiles -> Res.string.admin_upload_books_too_many_files_title
        is SelectionRefusal.TooLarge -> Res.string.admin_upload_books_too_large_title
        is SelectionRefusal.FileTooLarge -> Res.string.admin_upload_books_file_too_large_title
    }

@Composable
private fun SelectionRefusal.body(): String =
    when (this) {
        is SelectionRefusal.TooManyFiles -> {
            stringResource(
                Res.string.admin_upload_books_too_many_files_body,
                UploadLimits.MAX_FILES,
                count,
            )
        }

        is SelectionRefusal.TooLarge -> {
            stringResource(
                Res.string.admin_upload_books_too_large_body,
                formatFileSize(UploadLimits.MAX_SESSION_BYTES),
                formatFileSize(bytes),
            )
        }

        is SelectionRefusal.FileTooLarge -> {
            stringResource(
                Res.string.admin_upload_books_file_too_large_body,
                filename,
                formatFileSize(bytes),
                formatFileSize(UploadLimits.MAX_FILE_BYTES),
            )
        }
    }

@Composable
private fun IdleContent(
    onChooseFolder: () -> Unit,
    onChooseFiles: () -> Unit,
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
            text = stringResource(Res.string.admin_upload_books_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        ListenUpButton(
            onClick = onChooseFolder,
            text = stringResource(Res.string.admin_upload_books_choose_folder),
            leadingIcon = Icons.Outlined.FolderOpen,
            modifier = Modifier.fillMaxWidth(),
        )
        ListenUpButton(
            onClick = onChooseFiles,
            text = stringResource(Res.string.admin_upload_books_choose_files),
            leadingIcon = Icons.Outlined.InsertDriveFile,
            // Secondary: a folder is the better answer almost always, so only one of these two
            // should read as the primary action.
            filled = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UploadingContent(
    state: UploadBooksUiState.Uploading,
    onCancel: () -> Unit,
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
            text = stringResource(Res.string.admin_upload_books_uploading, state.filename),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text =
                stringResource(
                    Res.string.admin_upload_books_file_progress,
                    state.fileIndex + 1,
                    state.fileCount,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        // A known total gets a real bar; an unknown one gets an honest indeterminate sweep rather
        // than a bar frozen at zero while bytes are plainly moving.
        val fraction = state.fraction
        if (fraction != null) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(32.dp))
        ListenUpButton(
            onClick = onCancel,
            text = stringResource(Res.string.admin_upload_books_cancel),
            filled = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FinalizingContent(modifier: Modifier = Modifier) {
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
            text = stringResource(Res.string.admin_upload_books_finalizing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(38.dp))
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FinishedContent(
    state: UploadBooksUiState.Finished,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        ScallopBadge(size = 104.dp, containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.admin_upload_books_finished_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.admin_upload_books_imported, state.imported.size),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // A duplicate is a correct outcome, not a failure — it gets neutral words and neutral colour.
        if (state.duplicates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.admin_upload_books_duplicates, state.duplicates.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.failed.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.admin_upload_books_failures, state.failed.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        ListenUpButton(
            onClick = onDone,
            text = stringResource(Res.string.admin_upload_books_done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FailedContent(
    message: String,
    onTryAgain: () -> Unit,
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
            text = stringResource(Res.string.admin_upload_books_failed_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        ListenUpButton(
            onClick = onTryAgain,
            text = stringResource(Res.string.admin_upload_books_choose_folder),
            leadingIcon = Icons.Outlined.FolderOpen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
