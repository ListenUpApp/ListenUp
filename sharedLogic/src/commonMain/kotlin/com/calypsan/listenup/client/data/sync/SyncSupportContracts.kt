package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/** Utility contract for local image download/cache operations. */
internal interface ImageDownloaderContract {
    suspend fun deleteCover(bookId: BookId): AppResult<Unit>

    suspend fun downloadCover(bookId: BookId): AppResult<Boolean>

    suspend fun downloadContributorImage(contributorId: String): AppResult<Boolean>

    fun getContributorImagePath(contributorId: String): String?

    suspend fun downloadSeriesCover(seriesId: String): AppResult<Boolean>

    suspend fun downloadSeriesCovers(seriesIds: List<String>): AppResult<List<String>>

    suspend fun downloadUserAvatar(
        userId: String,
        forceRefresh: Boolean = false,
    ): AppResult<Boolean>

    fun getUserAvatarPath(userId: String): String?

    suspend fun deleteUserAvatar(userId: String): AppResult<Unit>
}

/**
 * Per-table `MAX(revision)` snapshot taken immediately before a sync reconcile.
 *
 * Server revisions are monotonic per domain, so every row the reconcile applies lands with a
 * revision strictly above the corresponding watermark — comparing post-reconcile rows against
 * this snapshot yields exactly the set the reconcile changed (including tombstones, which also
 * advance `revision`).
 */
internal data class SearchIndexWatermark(
    val booksRevision: Long,
    val contributorsRevision: Long,
    val seriesRevision: Long,
    val genresRevision: Long,
)

/** Utility contract for rebuilding local full-text-search tables. */
internal interface FtsPopulatorContract {
    suspend fun rebuildAll()

    /**
     * Rebuild the search index only when it is empty — the startup self-heal. A no-op when the
     * index is already populated, so it is cheap to call on every engine start.
     */
    suspend fun rebuildIfEmpty()

    /** Snapshot the per-table revision watermark — call immediately BEFORE a reconcile. */
    suspend fun snapshotWatermark(): SearchIndexWatermark

    /**
     * Incrementally refresh the search index for rows changed since [watermark] — call AFTER the
     * reconcile. Reindexes only the changed books (expanding changed contributors/series/genres to
     * their books via the junction tables) and rebuilds contributors_fts/series_fts only when those
     * domains changed. Falls back to [rebuildAll] when the delta exceeds the incremental threshold
     * (e.g. initial population). A no-op when nothing changed.
     */
    suspend fun refreshSince(watermark: SearchIndexWatermark)

    /**
     * A debounce-able signal that fires whenever searchable content changes in Room — a live SSE
     * edit, a catch-up apply, anything that writes `books`/`contributors`/`series`/`genres`. The
     * consumer snapshots a watermark and calls [refreshSince] on the debounced edge so offline search
     * follows live edits without waiting for a scan or a full resync. The emitted value has no
     * meaning; only the invalidation does.
     */
    fun observeContentChanges(): Flow<Unit>
}
