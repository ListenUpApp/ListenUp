package com.calypsan.listenup.client.domain.model

/**
 * A book in the admin inbox awaiting review.
 *
 * Books land here when scanned if inbox workflow is enabled.
 * Admins can stage collection assignments and release when ready.
 */
data class InboxBook(
    /** Book ID */
    val id: String,
    /** Book title */
    val title: String,
    /** Primary author name */
    val author: String?,
    /** Cover image URL (relative path) */
    val coverUrl: String?,
    /** Total duration in milliseconds */
    val duration: Long,
    /** Collection IDs staged for assignment on release */
    val stagedCollectionIds: List<String>,
    /** Staged collections with names for display */
    val stagedCollections: List<StagedCollection>,
    /** When the book was scanned */
    val scannedAt: String,
)

/**
 * A collection staged for assignment to an inbox book.
 */
data class StagedCollection(
    val id: String,
    val name: String,
)

/**
 * Result of releasing books from the inbox.
 */
data class InboxReleaseResult(
    /** Total number of books released */
    val released: Int,
    /** Number of books made publicly visible (no collections) */
    val publicCount: Int,
    /** Number of collection assignments made */
    val toCollections: Int,
)
