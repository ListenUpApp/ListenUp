package com.calypsan.listenup.server.media

/** An image type the server stores, identified by its leading bytes — never by a declared type. */
internal enum class ImageKind(
    val ext: String,
    val contentType: String,
) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    GIF("gif", "image/gif"),
    ;

    companion object {
        /** The kind [bytes] start as, or null when they are none of these. */
        fun sniff(bytes: ByteArray): ImageKind? =
            when {
                bytes.size < MIN_SNIFF_BYTES -> null
                bytes.startsWith(JPEG_MAGIC) -> JPEG
                bytes.startsWith(PNG_MAGIC) -> PNG
                bytes.startsWith(GIF_MAGIC) -> GIF
                isWebp(bytes) -> WEBP
                else -> null
            }

        // WebP is a RIFF container: "RIFF" at 0..3, a 4-byte size, then "WEBP" at 8..11.
        private fun isWebp(bytes: ByteArray): Boolean =
            bytes.size >= WEBP_MIN_BYTES &&
                bytes.startsWith(RIFF_MAGIC) &&
                bytes.sliceArray(WEBP_TAG_OFFSET until WEBP_MIN_BYTES).contentEquals(WEBP_TAG)

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        private const val MIN_SNIFF_BYTES = 4
        private const val WEBP_TAG_OFFSET = 8
        private const val WEBP_MIN_BYTES = 12

        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        private val GIF_MAGIC = "GIF8".encodeToByteArray()
        private val RIFF_MAGIC = "RIFF".encodeToByteArray()
        private val WEBP_TAG = "WEBP".encodeToByteArray()
    }
}
