package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The cover on Book Edit — the web analogue of Android's tappable cover in the identity header.
 *
 * The cover itself is the button: click it (or, later tasks, drop an image on it) to choose a
 * replacement. The current artwork comes from the server via [coverUrl]; a pending replacement
 * renders from [BookEditUiState.pendingCoverData] — never from `displayCoverPath`, because the
 * browser's ImageStorage is bookkeeping-only and its `browser://` paths have no bytes behind
 * them.
 */
@Composable
fun CoverField(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Div(attrs = { classes("cover-field") }) {
        Button(attrs = {
            classes("cover-pick")
            attr("type", "button")
            attr("aria-label", "Change cover")
            if (state.isUploadingCover) attr("disabled", "")
        }) {
            Cover(
                title = state.title,
                imageUrl = coverUrl(state.bookId, state.coverHash, width = COVER_EDIT_FETCH_WIDTH),
                size = COVER_EDIT_SIZE,
            )
        }
        Span(attrs = { classes("cover-hint") }) { Text("Click the cover or drop an image on it") }
    }
}

private const val COVER_EDIT_SIZE = 180

/** 2× the rendered size, so the derivative the server picks stays sharp on dense displays. */
private const val COVER_EDIT_FETCH_WIDTH = 360
