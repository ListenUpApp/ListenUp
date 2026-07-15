package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.core.BookId

/**
 * Offline-first book editor.
 *
 * Every edit surface — the metadata PATCH and each replace-set (contributors, series, genres,
 * chapters, collections, cover removal) — writes its optimistic Room merge and enqueues one durable
 * [BookMutation] on the single `books` outbox channel, in ONE transaction (via [OfflineEditor.edit]).
 * The edit therefore persists and replays on reconnect rather than failing offline with a
 * [com.calypsan.listenup.api.error.ServerConnectError], and the user sees the change immediately.
 *
 * Riding one channel is load-bearing: the outbox's per-entity FIFO replays a book's edits in order,
 * and the `books`-keyed anti-flicker shield defers every inbound book echo while any edit is in
 * flight — so a stale echo never reverts the optimistic state. The authoritative state arrives via
 * the book's own SSE echo once the op drains and reconciles through the books domain
 * ([com.calypsan.listenup.client.data.sync.domains.booksDomain]); the outbox sender binding
 * ([com.calypsan.listenup.client.di.clientSyncModule]) dispatches each variant to its RPC.
 *
 * `setBookCollections` is the one exception to shield coverage: it rides the `books` channel for FIFO
 * ordering, but its authoritative echo flows through the `collection_books` domain, which the
 * `books`-keyed shield does not cover. Convergence still holds (the membership echo is
 * server-authoritative and last-write-wins); only a narrow stale-echo flicker window is possible.
 */
internal class BookEditRepositoryImpl(
    private val offlineEditor: OfflineEditor,
    private val localApply: BookMutationLocalApply,
    private val bookDao: BookDao,
) : BookEditRepository {
    override suspend fun updateBook(
        id: BookId,
        patch: BookUpdate,
    ): AppResult<Unit> = edit(id, BookMutation.Update(patch))

    override suspend fun setBookContributors(
        id: BookId,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit> = edit(id, BookMutation.SetContributors(contributors))

    override suspend fun setBookSeries(
        id: BookId,
        series: List<BookSeriesInput>,
    ): AppResult<Unit> = edit(id, BookMutation.SetSeries(series))

    override suspend fun setBookGenres(
        id: BookId,
        genres: List<BookGenreInput>,
    ): AppResult<Unit> = edit(id, BookMutation.SetGenres(genres))

    /**
     * Pre-validate the chapter set against the SAME set-level invariants the server enforces
     * ([com.calypsan.listenup.api.BookService.setBookChapters]) BEFORE the optimistic write. An
     * offline-first edit that failed server validation would otherwise apply optimistically, dead-letter
     * on drain, and revert — a jarring "it saved, then un-saved" flicker. Rejecting up front returns a
     * typed [BookError.InvalidInput] with no Room write and no queued op, so the user gets the same
     * immediate rejection they would online.
     */
    override suspend fun setBookChapters(
        id: BookId,
        chapters: List<ChapterInput>,
    ): AppResult<Unit> {
        // totalDuration for the `startTime < duration` bound. Null (book not yet in Room) skips only
        // that bound — the size and strictly-increasing checks still run, and the server remains the
        // final authority on drain.
        val totalDuration = bookDao.getById(id)?.totalDuration
        validateChapterSet(chapters, totalDuration)?.let { return AppResult.Failure(it) }
        return edit(id, BookMutation.SetChapters(chapters))
    }

    override suspend fun setBookTierLabels(
        id: BookId,
        bookTierLabel: String?,
        partTierLabel: String?,
    ): AppResult<Unit> = edit(id, BookMutation.SetTierLabels(bookTierLabel, partTierLabel))

    override suspend fun deleteBookCover(id: BookId): AppResult<Unit> = edit(id, BookMutation.DeleteCover)

    override suspend fun setBookCollections(
        id: BookId,
        collectionIds: List<String>,
    ): AppResult<Unit> = edit(id, BookMutation.SetCollections(collectionIds))

    /** Apply [mutation]'s optimistic Room merge and enqueue it on the `books` channel, atomically. */
    private suspend fun edit(
        id: BookId,
        mutation: BookMutation,
    ): AppResult<Unit> =
        offlineEditor.edit(OutboxChannels.Books, id.value, mutation) {
            localApply.apply(id, mutation)
        }
}

/**
 * The largest chapter set a book may carry — mirrors `BookServiceImpl.MAX_CHAPTERS_PER_BOOK`. Client
 * and server must agree so an offline edit is rejected up front with the same verdict the server
 * would give on drain.
 */
private const val MAX_CHAPTERS_PER_BOOK = 5000

/**
 * Set-level chapter invariants, mirroring `BookServiceImpl.validateChapterSet` (per-row shape is
 * already enforced by [ChapterInput]'s `init`). Returns the typed failure, or `null` when valid:
 *  - the set is capped at [MAX_CHAPTERS_PER_BOOK],
 *  - an empty set is valid (clears chapters),
 *  - `startTime`s must be strictly increasing (sorted AND distinct),
 *  - each `startTime` must fall before [bookDurationMs] (skipped when it is `null` — the book's
 *    duration isn't known locally, so the server backstops that one bound on drain).
 */
internal fun validateChapterSet(
    chapters: List<ChapterInput>,
    bookDurationMs: Long?,
): BookError.InvalidInput? {
    if (chapters.size > MAX_CHAPTERS_PER_BOOK) {
        return BookError.InvalidInput(
            debugInfo = "chapters: size ${chapters.size} exceeds max $MAX_CHAPTERS_PER_BOOK",
        )
    }
    if (chapters.isEmpty()) return null
    val starts = chapters.map { it.startTime }
    if (starts != starts.sorted() || starts.toSet().size != starts.size) {
        return BookError.InvalidInput(debugInfo = "chapter starts must be strictly increasing")
    }
    if (bookDurationMs != null && chapters.any { it.startTime >= bookDurationMs }) {
        return BookError.InvalidInput(debugInfo = "chapter start beyond book duration")
    }
    return null
}
