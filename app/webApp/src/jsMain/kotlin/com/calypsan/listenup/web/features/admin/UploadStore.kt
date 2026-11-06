package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open upload session. */
class UploadSession(
    val state: StateFlow<UploadBooksUiState>,
    val onFilesPicked: (List<UploadCandidate>) -> Unit,
    val onCancel: () -> Unit,
    val onReset: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

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
