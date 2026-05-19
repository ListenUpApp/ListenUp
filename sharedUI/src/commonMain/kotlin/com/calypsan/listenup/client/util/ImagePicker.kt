package com.calypsan.listenup.client.util

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.imagepicker.ImagePickerResult

/**
 * Platform-agnostic image picker interface.
 *
 * Call [launch] to open the platform native image selection UI.
 * Results are delivered via the callback passed to [rememberImagePicker].
 */
interface ImagePicker {
    fun launch()
}

/**
 * Remember a platform-specific image picker.
 *
 * - **Android**: Uses the modern Photo Picker (API 33+) with fallback to GetContent.
 * - **Desktop**: Uses the native file dialog with image file filtering.
 *
 * @param onResult Callback invoked with the pick result (success, cancelled, or error)
 * @return [ImagePicker] that can be used to launch the picker via [ImagePicker.launch]
 */
@Composable
expect fun rememberImagePicker(onResult: (ImagePickerResult) -> Unit): ImagePicker
