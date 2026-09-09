package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.web.events.SyntheticDragEvent
import com.calypsan.listenup.web.readByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Text
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.File

/**
 * Artwork, and the way to replace it: the web analogue of a tappable cover in a native identity
 * header.
 *
 * The artwork renders in a fixed frame, which is its true aspect; a dropzone card beside it is the
 * pick affordance — click it, or drop an image on it, to choose a replacement. A pending pick
 * previews from its own bytes, because the upload does not happen until Save and the browser's
 * ImageStorage is bookkeeping-only: its `browser://` paths have no bytes behind them. Until one is
 * picked, [currentArt] draws whatever the caller considers current.
 *
 * ⛔ [pendingBytes] must be the same array instance across recompositions — every real caller
 * passes the one its ViewModel is holding, which is why this works. The object URL is remembered
 * keyed on it, and a `ByteArray` compares by identity, so a caller that rebuilds the array each
 * frame makes this field create and revoke a blob URL every frame. That is invisible on screen and
 * expensive enough to starve the browser's event loop.
 *
 * [onDiscardPick] is offered only while a pick is pending, and only when the caller passes one. It
 * discards the staged image — it does not remove artwork the server already holds, and a control
 * that said "Remove" would be promising something no ViewModel here does.
 */
@Composable
fun CoverPickerField(
    pendingBytes: ByteArray?,
    isUploading: Boolean,
    inputId: String,
    onPicked: (imageData: ByteArray, filename: String) -> Unit,
    onDiscardPick: (() -> Unit)? = null,
    currentArt: @Composable () -> Unit,
) {
    val previewUrl =
        remember(pendingBytes) {
            pendingBytes?.let { bytes -> URL.createObjectURL(Blob(arrayOf(bytes.unsafeCast<Int8Array>()))) }
        }
    DisposableEffect(previewUrl) {
        onDispose { previewUrl?.let(URL::revokeObjectURL) }
    }
    val scope = rememberCoroutineScope()
    var fileInput by remember { mutableStateOf<HTMLInputElement?>(null) }
    var dragOver by remember { mutableStateOf(false) }
    Div(attrs = { classes("cover-field") }) {
        Div(attrs = { classes("cover-art") }) {
            if (previewUrl != null) {
                Img(src = previewUrl, alt = "New cover preview", attrs = { classes("cover-preview") })
            } else {
                currentArt()
            }
        }
        Div(attrs = { classes("cover-pick-col") }) {
            Button(attrs = {
                classes("cover-pick")
                if (dragOver) classes("cover-drag")
                attr(ATTR_TYPE, VALUE_BUTTON)
                attr("aria-label", "Change cover")
                attr("title", "Change cover")
                disabledWhen(isUploading)
                onClick { fileInput?.click() }
                onDragOver { event ->
                    // While an upload is in flight, don't accept the drag at all — the browser
                    // then shows its native no-drop cursor instead of a highlight we'd ignore.
                    if (isUploading) return@onDragOver
                    event.preventDefault()
                    dragOver = true
                }
                onDragLeave { dragOver = false }
                onDrop { event ->
                    event.preventDefault()
                    dragOver = false
                    acceptedDroppedImage(event, isUploading)?.let { pickImage(scope, it, onPicked) }
                }
            }) {
                Div(attrs = { classes("cover-pick-disc") }) { Icon(WebIcon.Upload, size = UPLOAD_ICON_SIZE) }
                Div(attrs = { classes("cover-pick-main") }) { Text("Click to choose an image") }
                Div(attrs = { classes("cover-pick-sub") }) { Text("or drag and drop — JPG, PNG or WebP") }
            }
            if (onDiscardPick != null && pendingBytes != null) {
                Button(attrs = {
                    classes("btn-o", "cover-undo")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    disabledWhen(isUploading)
                    onClick { onDiscardPick() }
                }) { Text("Keep the current cover") }
            }
        }
        Input(type = InputType.File, attrs = {
            id(inputId)
            attr("accept", "image/*")
            style { property("display", "none") }
            ref { element ->
                fileInput = element
                onDispose { fileInput = null }
            }
            onChange { event ->
                val element = event.target as HTMLInputElement
                element.files?.item(0)?.let { file -> pickImage(scope, file, onPicked) }
                // Re-picking the same file must fire change again next time.
                element.value = ""
            }
        })
    }
}

/**
 * The file a drop should upload, or null: nothing while an upload is in flight,
 * nothing when no file was dropped, and never a non-image — a drop bypasses the
 * picker's accept filter, so this check is the only gate.
 */
private fun acceptedDroppedImage(
    event: SyntheticDragEvent,
    isUploading: Boolean,
): File? {
    if (isUploading) return null
    val file = event.dataTransfer?.files?.item(0) ?: return null
    return file.takeIf { it.type.startsWith("image/") }
}

/**
 * Read the picked file and hand its bytes on.
 *
 * A read failure here is a browser-local dead end (there is no AppError for "your own disk
 * refused") — it drops the pick; the form is untouched, so the reader just picks again.
 */
private fun pickImage(
    scope: CoroutineScope,
    file: File,
    onPicked: (ByteArray, String) -> Unit,
) {
    scope.launch {
        val bytes = file.readByteArray() ?: return@launch
        onPicked(bytes, file.name)
    }
}

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"

private const val UPLOAD_ICON_SIZE = 20
