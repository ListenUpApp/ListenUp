package com.calypsan.listenup.client.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import com.calypsan.listenup.core.IODispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/** JPEG quality for re-encoding picked images (see [decodePickedImageAsJpeg]). */
private const val JPEG_QUALITY = 90

/**
 * Android implementation of [ImagePicker].
 *
 * Uses the modern Android Photo Picker (API 33+) with automatic fallback
 * to GetContent for older devices or devices without Play Services.
 */
class ImagePickerState(
    private val context: Context,
    private val scope: CoroutineScope,
    onResult: (ImagePickerResult) -> Unit,
) : ImagePicker {
    /** The newest callback; the screen may recompose with a different lambda while the picker is open. */
    internal var onResult: (ImagePickerResult) -> Unit = onResult

    private var launchPicker: (() -> Unit)? = null

    internal fun setLauncher(launcher: () -> Unit) {
        launchPicker = launcher
    }

    /**
     * Launch the image picker.
     */
    override fun launch() {
        launchPicker?.invoke()
    }

    internal fun handleResult(uri: Uri?) {
        if (uri == null) {
            onResult(ImagePickerResult.Cancelled)
            return
        }

        scope.launch {
            val result = readImageFromUri(uri)
            // Back on the scope's own (main) dispatcher: the callback touches UI state.
            onResult(result)
        }
    }

    private suspend fun readImageFromUri(uri: Uri): ImagePickerResult {
        val filename = withContext(IODispatcher) { filenameOrNull(uri) } ?: "image.jpg"

        // Normalize to JPEG. Some Android devices store photos as HEIC/HEIF, which the server's
        // image validator rejects (it accepts JPEG/PNG/WebP by magic bytes only) -> 422 on upload.
        val data =
            decodePickedImageAsJpeg(context, uri, JPEG_QUALITY)
                ?: return ImagePickerResult.Error("Could not decode image")

        logger.debug { "Read image: filename=$filename, size=${data.size} (normalized to JPEG)" }

        return ImagePickerResult.Success(
            data = data,
            filename = filename,
            mimeType = "image/jpeg",
        )
    }

    /** A name is a nicety: a provider that refuses the query must not fail the pick. */
    private fun filenameOrNull(uri: Uri): String? =
        try {
            getFilenameFromUri(context, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Could not read the display name of a picked image from ${uri.authority.orEmpty()}" }
            null
        }

    private fun getFilenameFromUri(
        context: Context,
        uri: Uri,
    ): String? {
        var filename: String? = null

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    filename = cursor.getString(nameIndex)
                }
            }
        }

        return filename
    }
}

/**
 * Android actual implementation of [rememberImagePicker].
 *
 * Uses the modern Android Photo Picker (API 33+) when available, with automatic
 * fallback to the legacy content picker for older devices.
 */
@Composable
actual fun rememberImagePicker(onResult: (ImagePickerResult) -> Unit): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { ImagePickerState(context, scope, onResult) }
    SideEffect { state.onResult = onResult }

    // Check if Photo Picker is available (API 33+ or backported via Play Services)
    val isPhotoPickerAvailable =
        ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)

    if (isPhotoPickerAvailable) {
        // Modern Photo Picker (no permissions needed)
        val modernLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickVisualMedia(),
                onResult = { uri -> state.handleResult(uri) },
            )

        state.setLauncher {
            modernLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    } else {
        // Fallback for API 32 and devices without Photo Picker backport
        val legacyLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent(),
                onResult = { uri -> state.handleResult(uri) },
            )

        state.setLauncher {
            legacyLauncher.launch("image/*")
        }
    }

    return state
}
