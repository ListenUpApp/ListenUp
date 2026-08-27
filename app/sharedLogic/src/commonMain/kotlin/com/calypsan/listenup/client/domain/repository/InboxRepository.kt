@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.result.AppResult

/**
 * Repository contract for the admin collection inbox.
 *
 * The inbox is a system collection holding freshly-ingested books awaiting admin
 * triage. Both operations ride `CollectionService.listInbox` /
 * `CollectionService.releaseBooks` on the `@Rpc CollectionService` contract. Reads are
 * direct RPC fetches of the authoritative book-id set — the inbox is not mirrored into
 * Room.
 *
 * Implementations live in the data layer.
 */
interface InboxRepository {
    /** Returns the live (unreleased) book ids in the inbox for [libraryId]. */
    suspend fun listInbox(libraryId: String): AppResult<List<String>>

    /**
     * Releases the books keyed in [assignments] out of the inbox. Each entry maps a
     * book id to the collection ids it should be added to on release (an empty list
     * releases the book as publicly visible).
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
