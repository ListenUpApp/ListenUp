package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open upload session. */
class UploadSession(
    val state: StateFlow<UploadBooksUiState>,
    val onFilesPicked: (List<UploadCandidate>) -> Unit,
    val onCancel: () -> Unit,
    val onReset: () -> Unit,
    val close: () -> Unit,
)

/** How the upload screen gets its state. */
typealias OpenUpload = () -> UploadSession

/** The production source: the shared [UploadBooksViewModel]. */
fun graphUpload(koin: Koin): OpenUpload =
    {
        val viewModel = koin.get<UploadBooksViewModel>()
        val store = ViewModelStore().apply { put("upload", viewModel) }
        UploadSession(
            state = viewModel.state,
            onFilesPicked = viewModel::onFilesPicked,
            onCancel = viewModel::cancel,
            onReset = viewModel::reset,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedUpload(
    state: UploadBooksUiState,
    onFilesPicked: (List<UploadCandidate>) -> Unit = {},
    onCancel: () -> Unit = {},
    onReset: () -> Unit = {},
): OpenUpload =
    {
        UploadSession(
            state = MutableStateFlow(state),
            onFilesPicked = onFilesPicked,
            onCancel = onCancel,
            onReset = onReset,
            close = {},
        )
    }
