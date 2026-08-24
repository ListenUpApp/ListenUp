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

    /**
     * The write was refused because its target does not resolve inside any live library folder.
     *
     * This is the broker enforcing the "inside" half of its own promise: it is the sole writer
     * inside library folders, so a path that leaves them is refused before any byte moves. The
     * check runs on the *resolved* path — `..` segments are normalised away and symbolic links
     * are followed first — because a raw string prefix would accept `<root>/../outside/x` and a
     * symlinked book directory would walk straight out of the library.
     *
     * [isRetryable] is `false`, deliberately in contrast to [Unavailable]: an unwritable mount may
     * heal on its own, but a target outside the library is a bug in the caller (or an attack) and
     * re-firing it changes nothing. Retry middleware must not spin on this, and it must not be
     * buried among ordinary mount flaps in the logs.
     */
    @Serializable
    @SerialName("LibraryWriteError.OutsideLibrary")
    data class OutsideLibrary(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : LibraryWriteError {
        override val message: String = "That location isn't inside your library."
        override val code: String = "LIBRARY_WRITE_OUTSIDE_LIBRARY"
        override val isRetryable: Boolean = false
    }
}
