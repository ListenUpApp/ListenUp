package com.calypsan.listenup.api.dto.uploads

/**
 * The ceilings one upload session may not exceed.
 *
 * These live in the contract rather than inside the server because the client needs them *before*
 * it starts: refusing an over-large selection up front costs a dialog, while discovering it
 * server-side costs however many gigabytes were already on the wire. The server remains the
 * enforcer — it re-checks every one of these per request, and a client that ignores them is simply
 * refused — but both sides now read the same numbers instead of guessing at each other's.
 */
object UploadLimits {
    /** Most files one session may stage. */
    const val MAX_FILES: Int = 1_000

    /** Most bytes one session may stage in total. */
    const val MAX_SESSION_BYTES: Long = 64L * 1024 * 1024 * 1024

    /** Most bytes any single file may carry. */
    const val MAX_FILE_BYTES: Long = 16L * 1024 * 1024 * 1024
}
