package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from [com.calypsan.listenup.server.librarywrite.LibraryWriteBroker] — the sole
 * component permitted to write inside library folders.
 */
@Serializable
sealed interface LibraryWriteError : AppError {
    /**
     * The library folder isn't writable right now — the mount may be read-only, offline, or out
     * of space. [isRetryable] is `true`: the underlying mount may come back on its own (e.g. a
     * network share reconnecting), so retry middleware can blindly re-fire the write.
     */
    @Serializable
    @SerialName("LibraryWriteError.Unavailable")
    data class Unavailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : LibraryWriteError {
        override val message: String = "This library folder isn't writable right now."
        override val code: String = "LIBRARY_WRITE_UNAVAILABLE"
        override val isRetryable: Boolean = true
    }
}
