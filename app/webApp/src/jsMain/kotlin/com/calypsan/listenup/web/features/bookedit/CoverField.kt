package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.coverUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume

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
    val scope = rememberCoroutineScope()
    var fileInput by remember { mutableStateOf<HTMLInputElement?>(null) }
    Div(attrs = { classes("cover-field") }) {
        Button(attrs = {
            classes("cover-pick")
            attr("type", "button")
            attr("aria-label", "Change cover")
            if (state.isUploadingCover) attr("disabled", "")
            onClick { fileInput?.click() }
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
        Input(type = InputType.File, attrs = {
            id("edit-cover-input")
            attr("accept", "image/*")
            style { property("display", "none") }
            ref { element ->
                fileInput = element
                onDispose { fileInput = null }
            }
            onChange { event ->
                val element = event.target as HTMLInputElement
                val file = element.files?.item(0)
                if (file != null) pickCover(scope, file, onEvent)
                // Re-picking the same file must fire change again next time.
                element.value = ""
            }
        })
        Span(attrs = { classes("cover-hint") }) { Text("Click the cover or drop an image on it") }
    }
}

/**
 * Read the picked file and hand its bytes to the shared ViewModel.
 *
 * A read failure here is a browser-local dead end (there is no AppError for "your own disk
 * refused") — it logs and drops the pick; the form is untouched, so the reader just picks again.
 */
private fun pickCover(
    scope: CoroutineScope,
    file: File,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    scope.launch {
        val bytes = file.readByteArray() ?: return@launch
        onEvent(BookEditUiEvent.UploadCover(imageData = bytes, filename = file.name))
    }
}

/** [FileReader] as a suspend call; null on a read error rather than an exception. */
private suspend fun File.readByteArray(): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        val reader = FileReader()
        reader.onload = {
            val buffer = reader.result.unsafeCast<ArrayBuffer>()
            continuation.resume(Int8Array(buffer).unsafeCast<ByteArray>())
        }
        reader.onerror = {
            console.error("Cover file could not be read: $name")
            continuation.resume(null)
        }
        reader.readAsArrayBuffer(this)
    }

private const val COVER_EDIT_SIZE = 180

/** 2× the rendered size, so the derivative the server picks stays sharp on dense displays. */
private const val COVER_EDIT_FETCH_WIDTH = 360
