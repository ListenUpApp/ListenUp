package com.calypsan.listenup.api.dto.scan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why the scanner could not turn a folder into a book.
 *
 * Each value maps to a distinct thing the admin would do about it, which is the only reason to
 * distinguish them: a folder with no audio wants files added or the folder removed, an unreadable
 * file wants permissions or a re-copy, and unusable tags want an edit. A reason the user cannot act
 * on differently does not deserve its own case.
 */
@Serializable
enum class ScanIssueReason {
    /** The folder held nothing the scanner recognises as audio. */
    @SerialName("NO_RECOGNIZED_AUDIO")
    NO_RECOGNIZED_AUDIO,

    /** A file is present but could not be opened or read through. */
    @SerialName("FILE_UNREADABLE")
    FILE_UNREADABLE,

    /** The audio opened, but its metadata could not be parsed. */
    @SerialName("METADATA_PARSE_FAILED")
    METADATA_PARSE_FAILED,

    /** Nothing in the tags or the path was usable as a title. */
    @SerialName("TITLE_INFERENCE_FAILED")
    TITLE_INFERENCE_FAILED,

    /** The scan failed here for a reason the server could not classify. */
    @SerialName("UNKNOWN")
    UNKNOWN,
}

/**
 * One folder the scanner walked but could not import, durably recorded.
 *
 * Before this existed, a book that failed to import produced a `logger.warn` line and nothing else
 * — it was absent from the library with no trace anywhere the user could see, which is the exact
 * shape of failure this app is not supposed to have. An issue row is the honest alternative: the
 * folder is named, the reason is stated, and it stays visible until it is fixed or dismissed.
 *
 * [rootRelPath] is relative to the library folder root, so it reads the way the user's own
 * directory tree reads. [detail] carries the per-instance specifics (which file, what the parser
 * said); [reason] carries the part worth branching on.
 *
 * [firstSeenAt] and [lastSeenAt] differ when a scan keeps re-encountering the same broken folder —
 * useful for telling "this has been wrong for a month" from "this appeared in today's scan".
 */
@Serializable
data class ScanIssue(
    @SerialName("id")
    val id: String,
    @SerialName("rootRelPath")
    val rootRelPath: String,
    @SerialName("reason")
    val reason: ScanIssueReason,
    @SerialName("detail")
    val detail: String? = null,
    @SerialName("firstSeenAt")
    val firstSeenAt: Long,
    @SerialName("lastSeenAt")
    val lastSeenAt: Long,
)
