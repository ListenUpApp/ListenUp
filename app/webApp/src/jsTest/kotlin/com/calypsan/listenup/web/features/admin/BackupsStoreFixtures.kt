package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.FileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.RawSink

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
