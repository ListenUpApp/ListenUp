package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreBackupViewModel
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileViewModel
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.api.dto.backup.BackupEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.RawSink
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * An open Backups screen.
 *
 * Two ViewModels behind one session, which is unusual and deliberate: `AdminBackupViewModel` owns
 * the list and `RestoreFromFileViewModel` owns the upload, but a reader sees one screen — the file
 * they pick becomes a backup in the same list. Splitting them into two sessions would put that
 * seam in the page, which is the one place it means nothing.
 */
@Suppress("LongParameterList")
class BackupsSession(
    val state: StateFlow<AdminBackupUiState>,
    val uploadState: StateFlow<RestoreFromFileUiState>,
    /** One-shot: a backup finished downloading and its bytes are ready to hand to the browser. */
    val downloadSaved: Flow<Unit>,
    /** One-shot: an uploaded file was staged as [BackupId] and is ready to restore from. */
    val uploaded: Flow<BackupId>,
    val onCreate: (includeImages: Boolean) -> Unit,
    val onDownload: (BackupId, RawSink) -> Unit,
    val onAskDelete: (BackupInfo) -> Unit,
    val onDismissDelete: () -> Unit,
    val onDelete: (BackupInfo) -> Unit,
    val onPickFile: (FileSource) -> Unit,
    val onResetUpload: () -> Unit,
    val onClearError: () -> Unit,
    val onRetry: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModels; specs hand over states. */
typealias OpenBackups = () -> BackupsSession

/** The production source: the list ViewModel and the upload ViewModel, over the started graph. */
fun graphBackups(koin: Koin): OpenBackups =
    {
        val backups = koin.get<AdminBackupViewModel>()
        val upload = koin.get<RestoreFromFileViewModel>()
        val store =
            ViewModelStore().apply {
                put(BACKUPS_STORE_KEY, backups)
                put(UPLOAD_STORE_KEY, upload)
            }
        backups.loadBackups()
        BackupsSession(
            state = backups.state,
            uploadState = upload.state,
            downloadSaved = backups.downloadSaved,
            uploaded = upload.navigation,
            onCreate = backups::createBackup,
            onDownload = backups::downloadBackup,
            onAskDelete = backups::showDeleteConfirmation,
            onDismissDelete = backups::dismissDeleteConfirmation,
            onDelete = backups::deleteBackup,
            onPickFile = upload::onFilePicked,
            onResetUpload = upload::reset,
            onClearError = backups::clearError,
            onRetry = backups::loadBackups,
            close = store::clear,
        )
    }

/** A session over states that never change — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedBackups(
    state: AdminBackupUiState = AdminBackupUiState.Loading,
    uploadState: RestoreFromFileUiState = RestoreFromFileUiState.Idle,
    downloadSaved: Flow<Unit> = emptyFlow(),
    uploaded: Flow<BackupId> = emptyFlow(),
    onCreate: (Boolean) -> Unit = {},
    onDownload: (BackupId, RawSink) -> Unit = { _, _ -> },
    onAskDelete: (BackupInfo) -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onDelete: (BackupInfo) -> Unit = {},
    onPickFile: (FileSource) -> Unit = {},
    onResetUpload: () -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenBackups =
    {
        BackupsSession(
            state = MutableStateFlow(state),
            uploadState = MutableStateFlow(uploadState),
            downloadSaved = downloadSaved,
            uploaded = uploaded,
            onCreate = onCreate,
            onDownload = onDownload,
            onAskDelete = onAskDelete,
            onDismissDelete = onDismissDelete,
            onDelete = onDelete,
            onPickFile = onPickFile,
            onResetUpload = onResetUpload,
            onClearError = onClearError,
            onRetry = onRetry,
            close = {},
        )
    }

/** An open restore: the confirmation, the progress, and the teardown. */
class RestoreSession(
    val state: StateFlow<RestoreBackupUiState>,
    val progress: StateFlow<BackupEvent?>,
    val onRequest: () -> Unit,
    val onCancel: () -> Unit,
    val onConfirm: () -> Unit,
    val close: () -> Unit,
)

/** How the restore page gets its state, keyed on which backup. */
typealias OpenRestore = (backupId: String) -> RestoreSession

/**
 * The production source: [RestoreBackupViewModel], built for [backupId].
 *
 * The id is a constructor parameter, so — as with a collection's detail — a session cannot be
 * repointed and the route keys on the id.
 */
fun graphRestore(koin: Koin): OpenRestore =
    { backupId ->
        val viewModel = koin.get<RestoreBackupViewModel> { parametersOf(backupId) }
        val store = ViewModelStore().apply { put(backupId, viewModel) }
        RestoreSession(
            state = viewModel.state,
            progress = viewModel.progress,
            onRequest = viewModel::requestRestore,
            onCancel = viewModel::cancelRestore,
            onConfirm = viewModel::confirmRestore,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedRestore(
    state: RestoreBackupUiState = RestoreBackupUiState.Idle(),
    progress: BackupEvent? = null,
    onRequest: () -> Unit = {},
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit = {},
): OpenRestore =
    {
        RestoreSession(
            state = MutableStateFlow(state),
            progress = MutableStateFlow(progress),
            onRequest = onRequest,
            onCancel = onCancel,
            onConfirm = onConfirm,
            close = {},
        )
    }

private const val BACKUPS_STORE_KEY = "admin-backups"

private const val UPLOAD_STORE_KEY = "admin-backup-upload"
