package com.calypsan.listenup.client.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Android implementation of [ImagePicker].
 *
 * Uses the modern Android Photo Picker (API 33+) with automatic fallback
 * to GetContent for older devices or devices without Play Services.
 */
class ImagePickerState(
    private val context: Context,
    private val onResult: (ImagePickerResult) -> Unit,
) : ImagePicker {
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

        try {
            val result = readImageFromUri(context, uri)
            onResult(result)
        } catch (e: Exception) {
            logger.error(e) { "Failed to read image from URI: $uri" }
            onResult(ImagePickerResult.Error("Failed to read image: ${e.message}"))
        }
    }

    private fun readImageFromUri(
        context: Context,
        uri: Uri,
    ): ImagePickerResult {
        val contentResolver = context.contentResolver

        // Get filename from URI
        val filename = getFilenameFromUri(context, uri) ?: "image.jpg"

        // Get MIME type
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"

        // Read the image data
        val data =
            contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: return ImagePickerResult.Error("Could not open image stream")

        if (data.isEmpty()) {
            return ImagePickerResult.Error("Image data is empty")
        }

        logger.debug { "Read image: filename=$filename, mimeType=$mimeType, size=${data.size}" }

        return ImagePickerResult.Success(
            data = data,
            filename = filename,
            mimeType = mimeType,
        )
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
    val state = remember { ImagePickerState(context, onResult) }

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
