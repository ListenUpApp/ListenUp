package com.calypsan.listenup.api.error

import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Errors surfacing from the embedded audio metadata parser
 * (`:server.embeddedmeta`).
 *
 * Subtypes carry structured payloads — clients/operators get more than a
 * free-text reason. [pathString] is `String`, not a kotlinx-io `Path`,
 * because path is not stably `@Serializable` across kotlinx-io versions
 * and these errors cross the wire.
 *
 * [UnsupportedFormat.format] (nullable [AudioFormat]) supports type-safe
 * aggregation in scan summaries — operators see "12 FLAC files (parser
 * not yet available), 3 unknown files" instead of opaque magic-byte
 * strings. Null when the file's magic bytes weren't recognised by the
 * detector at all; populated when the detector named the format but no
 * parser is registered for it.
 */
@Serializable
sealed interface AudioMetadataError : AppError {
    /** File magic bytes did not match a parsable format. */
    @Serializable
    @SerialName("AudioMetadataError.UnsupportedFormat")
    data class UnsupportedFormat(
        val pathString: String,
        val detectedMagic: String?,
        val format: AudioFormat? = null,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AudioMetadataError {
        override val message: String = "Unsupported audio format."
        override val code: String = "AUDIO_META_UNSUPPORTED_FORMAT"
        override val isRetryable: Boolean = false
    }

    /** Format detected but its header is malformed (bad size, bad version, bad magic in nested structure). */
    @Serializable
    @SerialName("AudioMetadataError.CorruptHeader")
    data class CorruptHeader(
        val pathString: String,
        val format: AudioFormat,
        val offset: Long,
        val expected: String,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AudioMetadataError {
        override val message: String = "Corrupt audio metadata header."
        override val code: String = "AUDIO_META_CORRUPT_HEADER"
        override val isRetryable: Boolean = false
    }

    /** File ended before the metadata structure declared it would. */
    @Serializable
    @SerialName("AudioMetadataError.TruncatedStream")
    data class TruncatedStream(
        val pathString: String,
        val format: AudioFormat,
        val expectedBytes: Long,
        val actualBytes: Long,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AudioMetadataError {
        override val message: String = "Audio file truncated before metadata complete."
        override val code: String = "AUDIO_META_TRUNCATED_STREAM"
        override val isRetryable: Boolean = false
    }

    /** Filesystem-level read failure (permission denied, transient IO). */
    @Serializable
    @SerialName("AudioMetadataError.IoError")
    data class IoError(
        val pathString: String,
        val ioMessage: String,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AudioMetadataError {
        override val message: String = "Could not read audio file."
        override val code: String = "AUDIO_META_IO_ERROR"
        override val isRetryable: Boolean = true
    }
}
