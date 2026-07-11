package com.calypsan.listenup.server.librarywrite

/**
 * Whether a library folder root is currently writable by [LibraryWriteBroker], as determined by
 * [LibraryWriteBroker.probe]. Server-internal for now — the admin surface that exposes this to
 * clients ships in a later phase, which will map it to a contract type then.
 */
sealed interface LibraryWriteStatus {
    /** The root accepted a probe write-and-delete cleanly. */
    data object Available : LibraryWriteStatus

    /** The root rejected the probe. [reason] is a short, loggable technical detail — not user-facing copy. */
    data class Unavailable(
        val reason: String,
    ) : LibraryWriteStatus
}
