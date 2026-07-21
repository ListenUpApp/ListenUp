package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for contributor access and user-edit mutations.
 *
 * Two surface categories:
 * - **Observation** — [getContributor], [listBooksByContributor] are safe to
 *   call repeatedly. Cache-miss reads for OUR contributor entities — clients
 *   observe Room locally; when a contributor is referenced but not yet synced,
 *   they call this service for the single-entity fallback.
 * - **Mutation** — [updateContributor], [deleteContributor] mutate server state;
 *   The sync firehose delivers the authoritative payload to all connected clients.
 *
 * External Audible/iTunes metadata lookups live on [MetadataLookupService] —
 * separate service because local reads (fast, no external calls) and external
 * lookups (slow, rate-limited, fallible) have different transport semantics.
 *
 * // TODO: gate mutations by user permissions when Multi-user lands
 */
@Rpc
interface ContributorService {
    // ── Observation (existing) ───────────────────────────────────────────────

    /**
     * Returns the contributor aggregate for [id], or `null` when no such
     * contributor exists on the server.
     *
     * The primary use case is a cache miss: a book aggregate references a
     * contributor the client's Room database hasn't synced yet. Rather than
     * showing an error, the client calls [getContributor] to fetch the entity
     * on demand and render immediately while sync catches up in the background.
     */
    suspend fun getContributor(id: ContributorId): AppResult<ContributorSyncPayload?>

    /**
     * Returns all books associated with [id] in rank-stable order.
     *
     * Clients hydrate book detail from Room for ids already cached and call
     * `BookService.getBook` for any that are not. A contributor with no books
     * returns an empty list — never a failure.
     */
    suspend fun listBooksByContributor(id: ContributorId): AppResult<List<BookSyncPayload>>

    // ── Mutation (new in Books-C1) ───────────────────────────────────────────

    /**
     * PATCH the contributor identified by [id] with [patch]. Returns
     * [com.calypsan.listenup.api.error.ContributorError.NotFound] when no such
     * contributor exists.
     *
     * On a name or sortName change, `BookSearchReindexer.reindexAllBooksForContributor`
     * runs after the commit so `book_search.contributor_names` reflects the new name.
     * Reindex failure is logged WARN and swallowed; the RPC returns Success regardless.
     */
    suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit>

    /**
     * Hard-deletes all `book_contributors` junction rows referencing [id],
     * re-upserts each affected book (so the book's payload reflects the loss),
     * then soft-deletes the contributor row. Books that lose their last
     * contributor stay as books with empty author lists.
     */
    suspend fun deleteContributor(id: ContributorId): AppResult<Unit>

    /**
     * Merges contributor [source] into contributor [target]. After this call:
     * - All `book_contributors` rows referencing [source] are re-linked to [target],
     *   with `credited_as` set to source's display name where it was previously NULL
     *   (preserves per-book credit history — books published as "Robert Galbraith"
     *   stay credited that way even after Galbraith merges into J.K. Rowling).
     * - All affected books are re-upserted with the new contributor reference.
     * - [target]'s aliases gain source's name + source's existing aliases (deduped
     *   case-insensitively; target's own name is excluded).
     * - [source] is soft-deleted.
     *
     * Returns [com.calypsan.listenup.api.error.ContributorError.MergeSelfTarget] when
     * `source == target`. Returns [com.calypsan.listenup.api.error.ContributorError.NotFound]
     * when either is missing or already tombstoned.
     *
     * On success the server emits one `book.Updated` per affected book, plus
     * `contributor.Updated(target)` and `contributor.Deleted(source)`.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun mergeContributors(
        source: ContributorId,
        target: ContributorId,
    ): AppResult<Unit>

    /**
     * Unmerges [aliasName] back out of the contributor identified by [contributorId].
     * Creates a fresh contributor with `name = aliasName`, re-links every
     * `book_contributors` row where `(contributor_id = contributorId AND credited_as = aliasName)`
     * to the new contributor (clearing `credited_as` so the new contributor's canonical
     * name displays), removes [aliasName] from the target's aliases, and re-upserts
     * affected books.
     *
     * Returns [com.calypsan.listenup.api.error.ContributorError.NotFound] when no
     * contributor with the given id exists. Returns
     * [com.calypsan.listenup.api.error.ContributorError.AliasNotFound] when the alias
     * isn't on the contributor. Returns the new contributor's [ContributorId] on Success.
     *
     * Zero-book edge case: if no `book_contributors` rows match the alias-creditedAs
     * combination, the new contributor is still created (with no linked books) and the
     * alias is removed from the target.
     *
     * // TODO: gate by user permissions when Multi-user lands
     */
    suspend fun unmergeContributor(
        contributorId: ContributorId,
        aliasName: String,
    ): AppResult<ContributorId>
}
