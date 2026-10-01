@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the admin collection inbox.
 *
 * The inbox is a system collection holding freshly-ingested books awaiting admin triage. Which books
 * are held is a Room fact — admins sync every INBOX membership — so [observeHeldBookIds] reads the
 * local mirror and works offline. Releasing and the scanner's issue list ride the `CollectionService`
 * and `ScannerService` RPCs.
 *
 * Implementations live in the data layer.
 */
interface InboxRepository {
    /**
     * The books currently held for review, oldest hold first. The one held set the inbox page, the
     * Library entry, the navigation badge and Book Detail all read. Always empty on a member's
     * device, which never receives INBOX rows.
     */
    fun observeHeldBookIds(): Flow<Set<BookId>>

    /** Returns the live (unreleased) book ids in the inbox for [libraryId]. */
    suspend fun listInbox(libraryId: String): AppResult<List<String>>

    /**
     * Releases the books keyed in [assignments] out of the inbox. Each entry maps a
     * book id to the collection ids it should be added to on release (an empty list
     * releases the book as publicly visible).
     * On success the books leave the local held set immediately, without waiting for the sync echo.
     */
    suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit>

    /**
     * The folders the scanner walked but could not import, oldest first.
     *
     * These are not books awaiting a decision — they are things that went wrong and produced no
     * book at all. Before this surface existed they were a log line and nothing else, which is why
     * they belong in the inbox whether or not the admin holds healthy books for review.
     */
    suspend fun listScanIssues(): AppResult<List<ScanIssue>>

    /** Stops showing the issue with [issueId]. */
    suspend fun dismissScanIssue(issueId: String): AppResult<Unit>
}
