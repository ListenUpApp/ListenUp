package com.calypsan.listenup.client.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.calypsan.listenup.core.AndroidFileSource
import com.calypsan.listenup.core.FileSource
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Result from the document picker.
 */
sealed interface DocumentPickerResult {
    /**
     * Document was successfully selected.
     *
     * @param fileSource Streaming source for reading the document content
     * @param filename The original filename
     * @param mimeType The MIME type of the document
     * @param size The size in bytes
     */
    data class Success(
        val fileSource: FileSource,
        val filename: String,
        val mimeType: String,
        val size: Long,
        val uri: Uri,
    ) : DocumentPickerResult

    /** User cancelled the picker. */
    data object Cancelled : DocumentPickerResult

    /** An error occurred. */
    data class Error(
        val message: String,
    ) : DocumentPickerResult
}

/**
 * State holder for the document picker functionality.
 *
 * Uses Android's Storage Access Framework (SAF) to let users pick documents
 * from anywhere on their device, including cloud storage providers.
 */
class DocumentPickerState(
    private val context: Context,
    private val onResult: (DocumentPickerResult) -> Unit,
) {
    private var launchPicker: (() -> Unit)? = null

    internal fun setLauncher(launcher: () -> Unit) {
        launchPicker = launcher
    }

    /**
     * Launch the document picker.
     */
    fun launch() {
        launchPicker?.invoke()
    }

    internal fun handleResult(uri: Uri?) {
        if (uri == null) {
            onResult(DocumentPickerResult.Cancelled)
            return
        }

        try {
            val result = readDocumentFromUri(context, uri)
            onResult(result)
        } catch (e: Exception) {
            logger.error(e) { "Failed to read document from URI: $uri" }
            onResult(DocumentPickerResult.Error("Failed to read document: ${e.message}"))
        }
    }

    private fun readDocumentFromUri(
        context: Context,
        uri: Uri,
    ): DocumentPickerResult {
        val contentResolver = context.contentResolver

        // Get file info from URI
        val metadata = queryDocumentMetadata(context, uri)
        val filename = metadata.filename
        val size = metadata.size

        // Get MIME type
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        // Verify we can open the document (don't read it into memory!)
        contentResolver.openInputStream(uri)?.close()
            ?: return DocumentPickerResult.Error("Could not open document stream")

        // Create a streaming file source - content will be read on demand during upload
        val fileSource =
            AndroidFileSource(
                contentResolver = contentResolver,
                uri = uri,
                filename = filename,
                size = size,
            )

        logger.debug { "Selected document: filename=$filename, mimeType=$mimeType, size=$size" }

        return DocumentPickerResult.Success(
            fileSource = fileSource,
            filename = filename,
            mimeType = mimeType,
            size = size ?: 0L,
            uri = uri,
        )
    }

    /** Display name and byte size resolved from a SAF document [uri]. */
    private data class DocumentMetadata(
        val filename: String,
        val size: Long?,
    )

    private fun queryDocumentMetadata(
        context: Context,
        uri: Uri,
    ): DocumentMetadata {
        var filename = "backup"
        var size: Long? = null

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            cursor.columnValue(OpenableColumns.DISPLAY_NAME)?.let { filename = cursor.getString(it) ?: filename }
            cursor.columnValue(OpenableColumns.SIZE)?.let { index ->
                val queriedSize = cursor.getLong(index)
                if (queriedSize > 0) size = queriedSize
            }
        }

        return DocumentMetadata(filename = filename, size = size)
    }
}

/** Returns the column index for [name], or `null` when the cursor lacks that column. */
private fun android.database.Cursor.columnValue(name: String): Int? = getColumnIndex(name).takeIf { it >= 0 }

/**
 * Remember a document picker state for selecting files from the device.
 *
 * Uses Android's Storage Access Framework (SAF) which allows users to pick
 * files from any storage provider, including local storage, cloud drives, etc.
 *
 * @param mimeTypes The MIME types to accept (default: all files)
 * @param onResult Callback invoked with the pick result
 * @return [DocumentPickerState] that can be used to launch the picker
 *
 * Usage:
 * ```
 * val documentPicker = rememberDocumentPicker(
 *     mimeTypes = arrayOf("application/zip", "application/gzip", "application/x-tar")
 * ) { result ->
 *     when (result) {
 *         is DocumentPickerResult.Success -> uploadDocument(result.data, result.filename)
 *         is DocumentPickerResult.Cancelled -> { /* User cancelled */ }
 *         is DocumentPickerResult.Error -> showError(result.message)
 *     }
 * }
 *
 * Button(onClick = { documentPicker.launch() }) {
 *     Text("Select Backup File")
 * }
 * ```
 */
@Composable
fun rememberDocumentPicker(
    mimeTypes: Array<String> = arrayOf("*/*"),
    onResult: (DocumentPickerResult) -> Unit,
): DocumentPickerState {
    val context = LocalContext.current
    val state = remember { DocumentPickerState(context, onResult) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
            onResult = { uri -> state.handleResult(uri) },
        )

    state.setLauncher {
        launcher.launch(mimeTypes)
    }

    return state
}

/**
 * Remember a document picker specifically for Audiobookshelf backup files.
 *
 * Accepts common backup file formats: .audiobookshelf, .tar.gz, .tgz, .zip
 *
 * @param onResult Callback invoked with the pick result
 * @return [DocumentPickerState] that can be used to launch the picker
 */
@Composable
fun rememberABSBackupPicker(onResult: (DocumentPickerResult) -> Unit): DocumentPickerState {
    // Accept common archive formats that ABS might use
    // Also accept */* as fallback since .audiobookshelf extension might not have a MIME type
    return rememberDocumentPicker(
        mimeTypes =
            arrayOf(
                "*/*", // Fallback for unknown extensions like .audiobookshelf
            ),
        onResult = onResult,
    )
}

/**
 * Remember a document picker specifically for ListenUp backup files (`.listenup.zip`).
 *
 * `.listenup.zip` files may not have a registered MIME type on all devices, so we accept
 * `application/zip` and `application/octet-stream` with a wildcard fallback.
 *
 * @param onResult Callback invoked with the pick result
 * @return [DocumentPickerState] that can be used to launch the picker
 */
@Composable
fun rememberListenUpBackupPicker(onResult: (DocumentPickerResult) -> Unit): DocumentPickerState =
    rememberDocumentPicker(
        mimeTypes = arrayOf("application/zip", "application/octet-stream", "*/*"),
        onResult = onResult,
    )
