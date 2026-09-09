package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.CoverPickerField
import com.calypsan.listenup.web.design.coverUrl

/**
 * The cover on Book Edit — this book's state and events bound to the shared [CoverPickerField].
 *
 * No discard control: Book Edit's ViewModel has no event that un-stages a pick, so offering one
 * would be a button with nothing behind it.
 */
@Composable
fun CoverField(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    CoverPickerField(
        pendingBytes = state.pendingCoverData,
        isUploading = state.isUploadingCover,
        inputId = "edit-cover-input",
        onPicked = { bytes, filename ->
            onEvent(BookEditUiEvent.UploadCover(imageData = bytes, filename = filename))
        },
    ) {
        Cover(
            title = state.title,
            imageUrl = coverUrl(state.bookId, state.coverHash, width = COVER_EDIT_FETCH_WIDTH),
            size = COVER_ART_WIDTH,
        )
    }
}

private const val COVER_ART_WIDTH = 132

/** 2× the rendered width, so the derivative the server picks stays sharp on dense displays. */
private const val COVER_EDIT_FETCH_WIDTH = 264
