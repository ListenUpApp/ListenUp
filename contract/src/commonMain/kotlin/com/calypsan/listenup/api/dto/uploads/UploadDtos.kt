package com.calypsan.listenup.api.dto.uploads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The state of one upload session: its server-minted [sessionId] plus what is staged in it so far.
 *
 * Returned when a session is created and again after every accepted file, so the client always
 * knows how much of its own quota it has spent without having to keep a parallel tally.
 */
@Serializable
data class UploadSessionSummary(
    @SerialName("sessionId")
    val sessionId: String,
    /** How many files are currently staged in the session. */
    @SerialName("fileCount")
    val fileCount: Int,
    /** Total bytes currently staged in the session. */
    @SerialName("totalBytes")
    val totalBytes: Long,
)

/**
 * The outcome of ingesting one upload session — one [UploadedBook] per book the scanner's grouper
 * found in the staged tree.
 *
 * A session is never all-or-nothing: one book can be refused as a duplicate while its neighbours
 * land, so the result is a per-book list rather than a single status. The staging directory is
 * removed either way — nothing is left half-uploaded for a later attempt to trip over.
 */
@Serializable
data class UploadFinalizeResult(
    @SerialName("books")
    val books: List<UploadedBook>,
)

/**
 * One book's outcome from a finalize.
 *
 * [rootRelPath] is the path the book landed at, relative to its library folder root — present
 * only for [UploadedBookStatus.IMPORTED]. [detail] carries the human-readable reason for a
 * non-imported outcome (the title of the book that already exists, or what went wrong), and is
 * null when the book imported cleanly.
 */
@Serializable
data class UploadedBook(
    /** The title the scanner resolved for the staged files — what the user will recognise. */
    @SerialName("title")
    val title: String,
    @SerialName("status")
    val status: UploadedBookStatus,
    @SerialName("rootRelPath")
    val rootRelPath: String? = null,
    @SerialName("detail")
    val detail: String? = null,
)

/** What became of one uploaded book. */
@Serializable
enum class UploadedBookStatus {
    /** The book moved into the library at its canonical path and is being ingested. */
    @SerialName("IMPORTED")
    IMPORTED,

    /**
     * The library already holds this book (matched by ASIN, chapter fingerprint, or title/author),
     * so nothing was written. Replacing an existing book is a deliberate follow-up, not a silent
     * side effect of uploading.
     */
    @SerialName("DUPLICATE")
    DUPLICATE,

    /** The move into the library failed; nothing of this book reached the library. */
    @SerialName("FAILED")
    FAILED,
}
