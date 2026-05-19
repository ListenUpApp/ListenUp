package com.calypsan.listenup.client.domain.imagepicker

/**
 * Result from picking an image from the device gallery.
 */
sealed interface ImagePickerResult {
    /**
     * Successfully picked an image.
     *
     * @property data Raw image bytes
     * @property filename Original filename or generated name
     * @property mimeType MIME type of the image (e.g., "image/jpeg")
     */
    data class Success(
        val data: ByteArray,
        val filename: String,
        val mimeType: String,
    ) : ImagePickerResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Success

            if (!data.contentEquals(other.data)) return false
            if (filename != other.filename) return false
            if (mimeType != other.mimeType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + mimeType.hashCode()
            return result
        }
    }

    /**
     * User cancelled the picker.
     */
    data object Cancelled : ImagePickerResult

    /**
     * Error occurred while picking or reading the image.
     */
    data class Error(
        val message: String,
    ) : ImagePickerResult
}
