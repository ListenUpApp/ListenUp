package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import kotlinx.coroutines.flow.MutableStateFlow

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
