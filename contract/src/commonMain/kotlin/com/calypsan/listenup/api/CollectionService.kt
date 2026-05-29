package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for collection lifecycle, membership, and sharing.
 *
 * Collections are user-owned, library-scoped groupings of books. Unlike tags
 * (a global cross-user curator model), collections have an owner and an explicit
 * ACL: the owner has implicit write access, and other users gain access only
 * through a [CollectionShareDto] grant carrying a [SharePermission].
 *
 * Three surface categories:
 * - **Observation** — [listCollections], [getCollection], [listCollectionBooks],
 *   [listShares] read state only and are safe to call repeatedly.
 * - **Membership mutation** — [createCollection], [renameCollection],
 *   [deleteCollection], [addBookToCollection], [removeBookFromCollection]
 *   mutate the collection or its book set.
 * - **Share mutation** — [shareCollection], [updateShare], [revokeShare]
 *   manage the per-user ACL; owner-only.
 *
 * Accessibility for [listCollections] is the union of the caller's owned
 * collections, collections shared with the caller, and — for admins — all
 * collections on the server.
 *
 * REST mirrors are defined in
 * [com.calypsan.listenup.api.resources.CollectionResources].
 */
@Rpc
interface CollectionService {
    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Returns every collection the caller can access: their owned collections,
     * collections shared with them, and — for admins — all collections.
     *
     * Each [CollectionSummary] carries a live [CollectionSummary.bookCount]
     * computed at query time and the caller's effective
     * [CollectionSummary.callerPermission].
     */
    suspend fun listCollections(): AppResult<List<CollectionSummary>>

    /**
     * Returns the collection identified by [id] as a [CollectionSummary].
     *
     * Fails when no collection with [id] exists or when the caller lacks read
     * access to it.
     */
    suspend fun getCollection(id: CollectionId): AppResult<CollectionSummary>

    /**
     * Returns up to [limit] book IDs that are members of the collection
     * identified by [id], excluding any membership rows that have been
     * soft-deleted.
     *
     * [limit] is clamped server-side. Default 500. Callers hydrate book detail
     * from Room for IDs already cached and call `BookService.getBook` for misses.
     *
     * Fails when no collection with [id] exists or when the caller lacks read
     * access to it.
     */
    suspend fun listCollectionBooks(
        id: CollectionId,
        limit: Int = 500,
    ): AppResult<List<BookId>>

    // ── Membership mutation ──────────────────────────────────────────────────

    /**
     * Creates a new collection named [name] in the library identified by
     * [libraryId], owned by the caller, and returns it as a [CollectionSummary].
     *
     * [name] must be between 1 and 200 characters.
     */
    suspend fun createCollection(
        libraryId: String,
        name: String,
    ): AppResult<CollectionSummary>

    /**
     * Updates the display name of the collection identified by [id] to [name]
     * and returns the updated [CollectionSummary].
     *
     * Owner-only. Fails when no collection with [id] exists, when the caller is
     * not the owner, or when [name] is invalid.
     */
    suspend fun renameCollection(
        id: CollectionId,
        name: String,
    ): AppResult<CollectionSummary>

    /**
     * Deletes the collection identified by [id], cascade-soft-deleting its
     * membership and share rows.
     *
     * Owner-only. Fails when no collection with [id] exists or when the caller
     * is not the owner.
     */
    suspend fun deleteCollection(id: CollectionId): AppResult<Unit>

    /**
     * Adds the book identified by [bookId] to the collection identified by [id].
     *
     * Idempotent: re-adding a book that is already a member (or re-adding a
     * previously removed book) clears any tombstone and succeeds without error.
     *
     * Requires [SharePermission.Write] (owners have it implicitly). Fails when
     * the collection or book does not exist or when the caller lacks write access.
     */
    suspend fun addBookToCollection(
        id: CollectionId,
        bookId: BookId,
    ): AppResult<Unit>

    /**
     * Removes the book identified by [bookId] from the collection identified by
     * [id] by soft-deleting its membership row.
     *
     * Idempotent: removing a book that is not a member succeeds without error.
     *
     * Requires [SharePermission.Write] (owners have it implicitly). Fails when
     * the collection does not exist or when the caller lacks write access.
     */
    suspend fun removeBookFromCollection(
        id: CollectionId,
        bookId: BookId,
    ): AppResult<Unit>

    // ── Share mutation ───────────────────────────────────────────────────────

    /**
     * Grants the user identified by [sharedWithUserId] access to the collection
     * identified by [id] at the given [permission] level and returns the
     * resulting [CollectionShareDto].
     *
     * Owner-only. Fails when no collection with [id] exists, when the caller is
     * not the owner, or when [sharedWithUserId] is the caller (self-share).
     */
    suspend fun shareCollection(
        id: CollectionId,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShareDto>

    /**
     * Updates the [permission] level of an existing share on the collection
     * identified by [id] for the user identified by [sharedWithUserId] and
     * returns the updated [CollectionShareDto].
     *
     * Owner-only. Fails when no collection with [id] exists, when the caller is
     * not the owner, or when no share exists for [sharedWithUserId].
     */
    suspend fun updateShare(
        id: CollectionId,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShareDto>

    /**
     * Revokes the share on the collection identified by [id] for the user
     * identified by [sharedWithUserId].
     *
     * Idempotent: revoking a share that does not exist succeeds without error.
     *
     * Owner-only. Fails when no collection with [id] exists or when the caller
     * is not the owner.
     */
    suspend fun revokeShare(
        id: CollectionId,
        sharedWithUserId: String,
    ): AppResult<Unit>

    /**
     * Returns every active share grant on the collection identified by [id] as
     * a list of [CollectionShareDto].
     *
     * Owner-only. Fails when no collection with [id] exists or when the caller
     * is not the owner.
     */
    suspend fun listShares(id: CollectionId): AppResult<List<CollectionShareDto>>
}
