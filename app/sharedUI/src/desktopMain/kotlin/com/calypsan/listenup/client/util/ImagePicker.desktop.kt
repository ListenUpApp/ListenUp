package com.calypsan.listenup.client.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.SwingUtilities

private val logger = KotlinLogging.logger {}

/**
 * Image file extensions we accept.
 */
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/**
 * Map file extension to MIME type.
 */
private fun mimeTypeForExtension(ext: String): String =
    when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

/**
 * Desktop implementation of [ImagePicker].
 *
 * Uses [java.awt.FileDialog] for a native OS file picker experience
 * (macOS gets the native Finder dialog, Linux gets GTK/native).
 */
private class DesktopImagePicker(
    private val onResult: (ImagePickerResult) -> Unit,
    private val scope: CoroutineScope,
) : ImagePicker {
    override fun launch() {
        scope.launch(Dispatchers.IO) {
            try {
                var directory: String? = null
                var filename: String? = null

                // FileDialog must be shown on the AWT event thread
                SwingUtilities.invokeAndWait {
                    val dialog = FileDialog(null as Frame?, "Select Image", FileDialog.LOAD)
                    dialog.filenameFilter =
                        FilenameFilter { _, name ->
                            val ext = name.substringAfterLast('.', "").lowercase()
                            ext in IMAGE_EXTENSIONS
                        }
                    // macOS ignores FilenameFilter, so also set the file filter string
                    dialog.file = IMAGE_EXTENSIONS.joinToString(";") { "*.$it" }
                    dialog.isVisible = true

                    directory = dialog.directory
                    filename = dialog.file
                    dialog.dispose()
                }

                val dir = directory
                val name = filename

                if (dir == null || name == null) {
                    onResult(ImagePickerResult.Cancelled)
                    return@launch
                }

                val file = File(dir, name)
                if (!file.exists() || !file.isFile) {
                    onResult(ImagePickerResult.Error("Selected file does not exist"))
                    return@launch
                }

                val data = file.readBytes()
                if (data.isEmpty()) {
                    onResult(ImagePickerResult.Error("Image file is empty"))
                    return@launch
                }

                val ext = name.substringAfterLast('.', "")
                val mimeType = mimeTypeForExtension(ext)

                logger.debug { "Read image: filename=$name, mimeType=$mimeType, size=${data.size}" }

                onResult(
                    ImagePickerResult.Success(
                        data = data,
                        filename = name,
                        mimeType = mimeType,
                    ),
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to pick image" }
                onResult(ImagePickerResult.Error("Failed to pick image: ${e.message}"))
            }
        }
    }
}

/**
 * Desktop actual implementation of [rememberImagePicker].
 *
 * Opens the native OS file dialog filtered to image files.
 */
@Composable
actual fun rememberImagePicker(onResult: (ImagePickerResult) -> Unit): ImagePicker {
    val scope = rememberCoroutineScope()
    return remember { DesktopImagePicker(onResult, scope) }
}
