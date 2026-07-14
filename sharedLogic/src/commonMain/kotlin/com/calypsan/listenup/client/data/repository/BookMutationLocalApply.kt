package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookContributorDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.BookSeriesDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds

/**
 * The optimistic Room merge for every [BookMutation], mirroring [com.calypsan.listenup.client.data.sync.domains.BookMirrorApply]'s
 * reconciliation apply so the local state a user sees immediately equals what the book's own SSE echo
 * will produce once the edit drains. Runs INSIDE
 * [com.calypsan.listenup.client.data.sync.OfflineEditor]'s transaction (composed with the outbox
 * enqueue), so a crash can never leave a committed local edit without its durable op.
 *
 * Two edits can't be perfectly mirrored offline because their authoritative identity is minted
 * server-side and is unknown until the echo:
 *  - a **new-by-name** contributor or series ([BookContributorInput.id] / [BookSeriesInput.id] null)
 *    can't be linked optimistically (no id to key the junction), so it surfaces only once the op
 *    drains and the echo lands; existing contributors/series link immediately.
 *  - a series' [BookSeriesInput.position] is rendered to the junction's `sequence` string with a
 *    best-effort format; the echo overwrites it with the server's canonical string.
 * The `books`-keyed in-flight shield defers the echo while the op is queued, so these approximations
 * are transient display only — the echo is always the final word.
 */
internal class BookMutationLocalApply(
    private val bookDao: BookDao,
    private val bookContributorDao: BookContributorDao,
    private val contributorDao: ContributorDao,
    private val bookSeriesDao: BookSeriesDao,
    private val seriesDao: SeriesDao,
    private val genreDao: GenreDao,
    private val chapterDao: ChapterDao,
    private val collectionBookDao: CollectionBookDao,
) {
    /** Apply [mutation]'s optimistic Room merge for the book [bookId]. */
    suspend fun apply(
        bookId: BookId,
        mutation: BookMutation,
    ) {
        when (mutation) {
            is BookMutation.Update -> applyUpdate(bookId, mutation.patch)
            is BookMutation.SetContributors -> applyContributors(bookId, mutation.contributors)
            is BookMutation.SetSeries -> applySeries(bookId, mutation.series)
            is BookMutation.SetGenres -> applyGenres(bookId, mutation.genres)
            is BookMutation.SetChapters -> applyChapters(bookId, mutation.chapters)
            is BookMutation.SetCollections -> applyCollections(bookId, mutation.collectionIds)
            is BookMutation.DeleteCover -> applyDeleteCover(bookId)
        }
    }

    /**
     * Merge the PATCH into the book row — non-null scalars replace, null leaves untouched — and union
     * the rescan-protected [UserEditedField] set, matching `BookServiceImpl.applyPatch` so the local
     * provenance is correct offline and before the SSE echo.
     */
    private suspend fun applyUpdate(
        bookId: BookId,
        patch: BookUpdate,
    ) {
        val existing = bookDao.getById(bookId) ?: return
        bookDao.upsert(
            existing.copy(
                title = patch.title ?: existing.title,
                sortTitle = patch.sortTitle ?: existing.sortTitle,
                subtitle = patch.subtitle ?: existing.subtitle,
                description = patch.description ?: existing.description,
                publisher = patch.publisher ?: existing.publisher,
                publishYear = patch.publishYear ?: existing.publishYear,
                language = patch.language ?: existing.language,
                isbn = patch.isbn ?: existing.isbn,
                asin = patch.asin ?: existing.asin,
                abridged = patch.abridged ?: existing.abridged,
                userEditedFields = existing.userEditedFields + patch.editedFields(),
            ),
        )
    }

    /**
     * Replace the book's contributor junctions — mirrors `BookMirrorApply.applyContributors`. Inputs
     * with a known [BookContributorInput.id] link immediately (a missing contributor row is
     * bootstrapped from the input so the FK holds); id-null inputs are auto-created server-side and
     * surface via the echo.
     */
    private suspend fun applyContributors(
        bookId: BookId,
        contributors: List<BookContributorInput>,
    ) {
        bookContributorDao.deleteContributorsForBook(bookId)
        for (input in contributors) {
            val contributorId = input.id ?: continue
            contributorEnsureExists(contributorId, input.name)
            bookContributorDao.insert(
                BookContributorCrossRef(
                    bookId = bookId,
                    contributorId = contributorId,
                    role = input.role,
                    creditedAs = input.creditedAs,
                ),
            )
        }
    }

    /**
     * Replace the book's series junctions — mirrors `BookMirrorApply.applySeries`. Inputs with a known
     * [BookSeriesInput.id] link immediately (a missing series row is bootstrapped); id-null inputs are
     * auto-created server-side and surface via the echo.
     */
    private suspend fun applySeries(
        bookId: BookId,
        series: List<BookSeriesInput>,
    ) {
        bookSeriesDao.deleteSeriesForBook(bookId)
        for (input in series) {
            val seriesId = input.id ?: continue
            seriesEnsureExists(seriesId, input.name)
            bookSeriesDao.insert(
                BookSeriesCrossRef(
                    bookId = bookId,
                    seriesId = seriesId,
                    sequence = input.position?.let(::formatSequence),
                ),
            )
        }
    }

    /**
     * Replace the book's genre junctions — mirrors `BookMirrorApply.applyGenres`. Genres are never
     * auto-created, so an id that isn't already a live genre in Room is skipped (the server rejects it
     * too); the book_genres FK stays satisfied.
     */
    private suspend fun applyGenres(
        bookId: BookId,
        genres: List<BookGenreInput>,
    ) {
        genreDao.deleteGenresForBook(bookId)
        val known = genres.filter { genreDao.getById(it.genreId.value) != null }
        if (known.isEmpty()) return
        genreDao.insertAllBookGenres(known.map { BookGenreCrossRef(bookId = bookId, genreId = it.genreId.value) })
    }

    /** Replace the book's chapter rows — mirrors `BookMirrorApply.applyChapters` exactly. */
    private suspend fun applyChapters(
        bookId: BookId,
        chapters: List<ChapterInput>,
    ) {
        chapterDao.deleteChaptersForBook(bookId)
        chapterDao.upsertAll(
            chapters.map { chapter ->
                ChapterEntity(
                    id = ChapterId(chapter.id),
                    bookId = bookId,
                    title = chapter.title,
                    duration = chapter.duration,
                    startTime = chapter.startTime,
                )
            },
        )
    }

    /**
     * Set the book's collection membership to exactly [collectionIds] — mirrors the server's diff
     * (`CollectionService.setBookCollections`): memberships not in the set are tombstoned, missing
     * ones are added. New rows are written as `revision = 0` stubs; the membership domain's own echo
     * supersedes them with the authoritative revision.
     */
    private suspend fun applyCollections(
        bookId: BookId,
        collectionIds: List<String>,
    ) {
        val now = currentEpochMilliseconds()
        val current = collectionBookDao.liveCollectionIdsForBook(bookId.value).toSet()
        val target = collectionIds.toSet()
        for (collectionId in current - target) {
            collectionBookDao.tombstone(
                collectionId = collectionId,
                bookId = bookId.value,
                deletedAt = now,
                revision = 0,
            )
        }
        for (collectionId in target - current) {
            collectionBookDao.upsert(
                CollectionBookEntity(
                    collectionId = collectionId,
                    bookId = bookId.value,
                    createdAt = now,
                    revision = 0,
                    deletedAt = null,
                ),
            )
        }
    }

    /**
     * Clear the book's cover pointer so the UI hides it immediately — mirrors the end state
     * `BookMirrorApply` reaches for a cover-removal echo (cover hash and local-presence marker both
     * nulled). The on-disk file is left untouched: the echo (or the cover reconciler) self-heals it,
     * so a rolled-back optimistic write never strands a book with a deleted file and no re-download
     * trigger.
     */
    private suspend fun applyDeleteCover(bookId: BookId) {
        val existing = bookDao.getById(bookId) ?: return
        bookDao.upsert(existing.copy(coverHash = null, coverDownloadedAt = null))
    }

    /** Bootstrap a minimal contributor row when it hasn't synced yet, so the junction FK holds. */
    private suspend fun contributorEnsureExists(
        id: ContributorId,
        name: String,
    ) {
        if (contributorDao.getById(id.value) != null) return
        val now = Timestamp(currentEpochMilliseconds())
        contributorDao.upsert(
            ContributorEntity(
                id = id,
                name = name,
                sortName = null,
                description = null,
                imagePath = null,
                revision = 0,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Bootstrap a minimal series row when it hasn't synced yet, so the junction FK holds. */
    private suspend fun seriesEnsureExists(
        id: SeriesId,
        name: String,
    ) {
        if (seriesDao.getById(id.value) != null) return
        val now = Timestamp(currentEpochMilliseconds())
        seriesDao.upsert(
            SeriesEntity(
                id = id,
                name = name,
                description = null,
                revision = 0,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

/** Render a series [position] to the junction's `sequence` string, dropping a trailing `.0`. */
private fun formatSequence(position: Double): String =
    if (position % 1.0 == 0.0) position.toLong().toString() else position.toString()

/**
 * The rescan-protected fields this patch edits — a non-null scalar means the user set it.
 *
 * Mirrors `BookServiceImpl.applyPatch` so the client's optimistic provenance matches what the server
 * records. Only [UserEditedField] scalars are mapped here; contributor and series provenance is set by
 * their own mutations.
 */
private fun BookUpdate.editedFields(): Set<UserEditedField> =
    buildSet {
        if (title != null) add(UserEditedField.TITLE)
        if (subtitle != null) add(UserEditedField.SUBTITLE)
        if (description != null) add(UserEditedField.DESCRIPTION)
    }
