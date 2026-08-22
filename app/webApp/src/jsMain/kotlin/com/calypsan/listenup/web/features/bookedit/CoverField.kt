package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.khronos.webgl.Int8Array
import org.w3c.dom.url.URL
import org.w3c.files.Blob

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
    val previewUrl =
        remember(state.pendingCoverData) {
            state.pendingCoverData?.let { bytes ->
                URL.createObjectURL(Blob(arrayOf(bytes.unsafeCast<Int8Array>())))
            }
        }
    DisposableEffect(previewUrl) {
        onDispose { previewUrl?.let(URL::revokeObjectURL) }
    }
    Div(attrs = { classes("cover-field") }) {
        Button(attrs = {
            classes("cover-pick")
            attr("type", "button")
            attr("aria-label", "Change cover")
            if (state.isUploadingCover) attr("disabled", "")
        }) {
            if (previewUrl != null) {
                Img(src = previewUrl, alt = "New cover preview", attrs = {
                    classes("cover-preview")
                    style {
                        property("width", "${COVER_EDIT_SIZE}px")
                        property("height", "${COVER_EDIT_SIZE}px")
                    }
                })
            } else {
                Cover(
                    title = state.title,
                    imageUrl = coverUrl(state.bookId, state.coverHash, width = COVER_EDIT_FETCH_WIDTH),
                    size = COVER_EDIT_SIZE,
                )
            }
        }
        Span(attrs = { classes("cover-hint") }) { Text("Click the cover or drop an image on it") }
    }
}

private const val COVER_EDIT_SIZE = 180

/** 2× the rendered size, so the derivative the server picks stays sharp on dense displays. */
private const val COVER_EDIT_FETCH_WIDTH = 360
