@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.domain.TierLabels
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for book data operations.
 *
 * Defines the public API for observing and refreshing books.
 * Used by ViewModels and enables testing via fake implementations.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface BookRepository {
    /**
     * No-op affordance for pull-to-refresh gestures.
     *
     * Book refresh is now driven entirely by the SSE event stream — there is no
     * client-initiated pull path. This method exists so that pull-to-refresh UI
     * surfaces have a call target; the implementation deliberately does nothing.
     * Removal of the pull-to-refresh affordance from the UI is deferred to Books-C.
     */
    suspend fun refreshBooks(): AppResult<Unit>

    /**
     * Get chapters for a book.
     */
    suspend fun getChapters(bookId: String): List<Chapter>

    /** Observe a book's chapters, ordered by start time. Emits on every local or synced change. */
    fun observeChapters(bookId: String): Flow<List<Chapter>>

    /**
     * Observe a book's renamable chapter-grouping tier vocabulary (e.g. "Part"/"Book" or
     * "Sequence"/"Era"). Emits [TierLabels] with both fields null while the book is absent from
     * the local mirror or the book has not named its tiers.
     */
    fun observeBookTierLabels(bookId: String): Flow<TierLabels>

    /**
     * Observe whether [id] is currently live (present, not tombstoned) in the local mirror.
     * Emits `false` once the book is removed/revoked. Drives the now-playing teardown.
     */
    fun observeIsBookLive(id: String): Flow<Boolean>

    /**
     * Observe random unstarted books for discovery.
     *
     * Used for "Discover Something New" section.
     * Returns books with no playback position, randomly ordered.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of discovery book summaries
     */
    fun observeRandomUnstartedBooks(limit: Int = 10): Flow<List<DiscoveryBook>>

    /**
     * Observe recently added books for discovery.
     *
     * Used for "Recently Added" section.
     * Returns books ordered by creation date descending.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of discovery book summaries
     */
    fun observeRecentlyAddedBooks(limit: Int = 10): Flow<List<DiscoveryBook>>

    /**
     * Observe all books as a reactive Flow of [BookListItem] projections.
     *
     * The list surface does not carry [BookDetail.genres]/[BookDetail.tags]/
     * [BookDetail.allContributors] — those are detail-only. Use this for home,
     * shelf, library, and any other list/grid consumer.
     *
     * Unlike [observeBookDetail], genre-only or tag-only edits to a book do
     * NOT cause this flow to re-emit — the list projection does not depend on
     * those edges.
     *
     * @return Flow emitting the current list-shaped book projection
     */
    fun observeBookListItems(): Flow<List<BookListItem>>

    /**
     * Get a single [BookListItem] by ID.
     *
     * @param id The book ID
     * @return List-shaped projection, or null if the book doesn't exist.
     */
    suspend fun getBookListItem(id: String): BookListItem?

    /**
     * Get multiple [BookListItem]s by IDs in a single batched query.
     *
     * Uses a SQL IN clause to batch-load. Results are unordered — callers that
     * need a specific order should re-sort on the returned list.
     *
     * @param ids Book IDs to fetch. Empty input returns an empty list.
     * @return List-shaped projections for the books that exist. May be smaller
     *   than the requested input if some IDs aren't in the local DB.
     */
    suspend fun getBookListItems(ids: List<String>): List<BookListItem>

    /**
     * Reactive counterpart to [getBookListItems] — emits whenever any of the
     * requested book rows change in Room.
     *
     * Used by [HomeRepository.observeContinueListening] to join position IDs to
     * book projections reactively: positions emit first, then this Flow keeps the
     * book side live so the Continue Listening shelf updates as books sync into
     * Room without an explicit re-subscription.
     *
     * @param ids Book IDs to observe. Empty input emits an empty list immediately.
     * @return Flow emitting the current set of matching [BookListItem]s; re-emits on any row change.
     */
    fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>>

    /**
     * Observe a single book's [BookDetail] reactively.
     *
     * Emits null if the book is absent; emits a [BookDetail] when the row
     * exists. Composes the book Flow with the book's genres Flow and tags
     * Flow — so genre and tag edits flow through to detail-screen consumers
     * without any additional subscription bookkeeping.
     *
     * @param id The book ID
     * @return Flow emitting the current detail shape, or null if absent.
     */
    fun observeBookDetail(id: String): Flow<BookDetail?>

    /**
     * Get a single book's [BookDetail] as a one-shot read.
     *
     * Snapshots the current book row + its genres + its tags. For reactive
     * detail-screen consumption prefer [observeBookDetail].
     *
     * @param id The book ID
     * @return Detail shape, or null if the book doesn't exist.
     */
    suspend fun getBookDetail(id: String): BookDetail?

    /**
     * Search books with an offline-first, never-stranded strategy.
     *
     * When online, runs server-side FTS5 search via [com.calypsan.listenup.api.BookService.searchBooks],
     * hydrating the ranked result ids from Room and preserving the server's rank
     * order. When offline — or when the server call fails — falls back to local
     * Room FTS5 so search always works.
     *
     * Emits exactly once: this is a query, not a live subscription.
     *
     * @param query The raw search query. A blank query yields an empty list.
     * @return Flow emitting the ranked [BookListItem] matches a single time.
     */
    fun search(query: String): Flow<List<BookListItem>>
}

/**
 * Lightweight book data for discovery sections.
 *
 * Contains only the fields needed for display in discovery carousels,
 * avoiding the overhead of loading full Book models with all contributors.
 */
data class DiscoveryBook(
    val id: String,
    val title: String,
    val authorName: String?,
    val coverPath: String?,
    val coverBlurHash: String?,
    val coverHash: String? = null,
    val createdAt: Long,
)
