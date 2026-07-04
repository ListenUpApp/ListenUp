package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookGenrePayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDocumentEntity
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.documents.DocumentStorage
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.domain.repository.ImageStorage

/**
 * Room mapping for the `books` domain — the aggregate apply behind [booksDomain].
 *
 * Applies server book payloads into Room as a single aggregate write. Two invariants
 * shape this apply:
 *
 * - **Atomic aggregate upsert.** A book is a root row plus child rows (chapters,
 *   contributors, series memberships, audio files). Every apply replaces the whole
 *   aggregate; atomicity comes from the composed handler's IMMEDIATE write
 *   transaction, inside which every method here runs — clients never observe a book
 *   with stale children or a partially-applied update.
 *
 * - **Bootstrap-only contributor/series writes.** The `contributors` and `series`
 *   sync domains own their respective rows. When a book event references a contributor
 *   or series that is not yet in Room, the handler inserts a minimal stub so the book
 *   renders immediately; the real row, with enrichment and a real revision, supersedes
 *   the stub when the contributor/series domain syncs. If the row already exists, it is
 *   left completely untouched — the book payload's embedded fields are never written
 *   back to an existing contributor or series row.
 */
internal class BookMirrorApply(
    private val database: ListenUpDatabase,
    private val mapper: BookEntityMapper,
    private val imageStorage: ImageStorage,
    // Optional: only the production graph (and the cache-GC test) supply it. When absent,
    // document-cache garbage collection is skipped — harmless for the many seam tests that
    // never write document cache files. See [applyDocuments].
    private val documentStorage: DocumentStorage? = null,
) : MirrorApply<BookSyncPayload> {
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.bookDao().softDelete(id = BookId(id), deletedAt = deletedAt, revision = revision)
        // The readership mirror is a cache keyed by book — sweep rows whose book
        // is no longer live (this one included; the stamp above precedes the sweep
        // inside the same write transaction).
        database.bookReadershipDao().deleteWhereBookNotLive()
    }

    override suspend fun tombstoneFromItem(item: BookSyncPayload) {
        tombstoneById(id = item.id, deletedAt = item.deletedAt ?: item.updatedAt, revision = item.revision)
    }

    /**
     * Upsert the whole book aggregate: the root row, then every child collection
     * replaced wholesale.
     */
    override suspend fun upsert(payload: BookSyncPayload) {
        val bookId = BookId(payload.id)
        val existing = database.bookDao().getById(bookId)

        val updatedEntity = mapper.toBookEntity(payload, existing)
        database.bookDao().upsert(updatedEntity)

        // The cover was replaced server-side when the content hash changes. The local cover file is
        // id-named and otherwise never re-downloaded (the downloader skips when a file exists), so it
        // would keep rendering the stale image. Drop it: the render then re-fetches the new cover and
        // the downloader repopulates it. Best-effort — a failed delete must not fail the sync write.
        if (existing != null && existing.coverHash != updatedEntity.coverHash) {
            val _ = imageStorage.deleteCover(bookId)
        }

        applyContributors(bookId, payload.contributors)
        applySeries(bookId, payload.series)
        applyGenres(bookId, payload.genres)
        applyChapters(bookId, payload.chapters)
        applyAudioFiles(payload.id, payload.audioFiles)
        applyDocuments(payload.id, payload.documents)
    }

    /**
     * Replace the book's `book_genres` junction set with the payload's genres.
     * Bootstrap-only writes a minimal [GenreEntity] stub if the genre row hasn't
     * arrived via its own substrate sync yet — matches the contributor/series
     * pattern so books with genres can render before the genre tree finishes
     * catching up. The substrate's `genre.Updated` then overwrites the stub
     * with authoritative fields.
     */
    private suspend fun applyGenres(
        bookId: BookId,
        genres: List<BookGenrePayload>,
    ) {
        database.genreDao().deleteGenresForBook(bookId)
        if (genres.isEmpty()) return
        for (genre in genres) {
            genreEnsureExists(genre)
        }
        database.genreDao().insertAllBookGenres(
            genres.map { BookGenreCrossRef(bookId = bookId, genreId = it.id) },
        )
    }

    /**
     * Insert a minimal genre stub if the row is missing. Substrate's
     * `genresDomain` overwrites the stub on the next catch-up; until
     * then the bootstrap row keeps the FK satisfied and the book renderable.
     */
    private suspend fun genreEnsureExists(payload: BookGenrePayload) {
        if (database.genreDao().getById(payload.id) != null) return
        database.genreDao().upsert(
            GenreEntity(
                id = payload.id,
                name = payload.name,
                slug = payload.slug,
                path = payload.path,
                parentId = null,
                depth = 0,
                sortOrder = 0,
                revision = 0L,
                deletedAt = null,
                createdAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            ),
        )
    }

    private suspend fun applyContributors(
        bookId: BookId,
        contributors: List<BookContributorPayload>,
    ) {
        database.bookContributorDao().deleteContributorsForBook(bookId)
        for (contributor in contributors) {
            contributorEnsureExists(contributor)
            database.bookContributorDao().insert(
                BookContributorCrossRef(
                    bookId = bookId,
                    contributorId = ContributorId(contributor.id),
                    role = contributor.role,
                    creditedAs = contributor.creditedAs,
                ),
            )
        }
    }

    private suspend fun applySeries(
        bookId: BookId,
        series: List<BookSeriesPayload>,
    ) {
        database.bookSeriesDao().deleteSeriesForBook(bookId)
        for (entry in series) {
            seriesEnsureExists(entry)
            database.bookSeriesDao().insert(
                BookSeriesCrossRef(
                    bookId = bookId,
                    seriesId = SeriesId(entry.id),
                    sequence = entry.sequence,
                ),
            )
        }
    }

    private suspend fun applyChapters(
        bookId: BookId,
        chapters: List<BookChapterPayload>,
    ) {
        database.chapterDao().deleteChaptersForBook(bookId)
        database.chapterDao().upsertAll(
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

    private suspend fun applyAudioFiles(
        bookId: String,
        audioFiles: List<BookAudioFilePayload>,
    ) {
        database.audioFileDao().deleteForBook(bookId)
        database.audioFileDao().upsertAll(
            audioFiles.map { file ->
                AudioFileEntity(
                    bookId = BookId(bookId),
                    index = file.index,
                    id = file.id,
                    filename = file.filename,
                    format = file.format,
                    codec = file.codec,
                    duration = file.duration,
                    size = file.size,
                    codecProfile = file.codecProfile,
                    spatial = file.spatial,
                    bitrate = file.bitrate,
                    sampleRate = file.sampleRate,
                    channels = file.channels,
                )
            },
        )
    }

    private suspend fun applyDocuments(
        bookId: String,
        documents: List<BookDocumentPayload>,
    ) {
        val newIds = documents.mapTo(mutableSetOf()) { it.id }

        // Capture the rows about to be replaced so their now-orphaned cache files can be
        // collected. Document UUIDs are minted fresh on every server rescan, so a re-scan
        // rotates every id — without this GC the `{documents}/{bookId}/` cache grows without
        // bound as `{old-uuid}.{ext}` files are stranded on disk.
        val staleDocs =
            if (documentStorage == null) {
                emptyList()
            } else {
                database.bookDocumentDao().getForBook(bookId).filter { it.id !in newIds }
            }

        database.bookDocumentDao().deleteForBook(bookId)
        database.bookDocumentDao().upsertAll(
            documents.map { doc ->
                BookDocumentEntity(
                    bookId = BookId(bookId),
                    index = doc.index,
                    id = doc.id,
                    filename = doc.filename,
                    format = doc.format,
                    size = doc.size,
                    hash = doc.hash,
                )
            },
        )

        // Best-effort cache GC — a failed delete must not fail the sync write (mirrors the
        // cover-file cleanup in [upsert]).
        staleDocs.forEach { documentStorage?.deleteCached(bookId, it.id, it.format) }
    }

    /**
     * Ensure a contributor row exists for [contributor]. When the row is already
     * present, leave it untouched — the `contributors` sync domain owns it
     * ([com.calypsan.listenup.client.data.sync.domains.contributorsDomain]).
     * When absent, insert a minimal bootstrap stub from the book payload's
     * embedded fields so the book renders immediately; the real row, with
     * enrichment and a real revision, supersedes the stub when the contributors
     * domain syncs. `revision = 0` marks the row as a not-yet-synced stub.
     */
    private suspend fun contributorEnsureExists(contributor: BookContributorPayload) {
        val existing = database.contributorDao().getById(contributor.id)
        if (existing != null) return
        val now = Timestamp(currentEpochMilliseconds())
        database.contributorDao().upsert(
            ContributorEntity(
                id = ContributorId(contributor.id),
                name = contributor.name,
                sortName = contributor.sortName,
                description = null,
                imagePath = null,
                revision = 0,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Ensure a series row exists for [series]. When already present, leave it
     * untouched — the `series` sync domain owns it
     * ([com.calypsan.listenup.client.data.sync.domains.seriesDomain]).
     * When absent, insert a minimal bootstrap stub so the book renders
     * immediately; the real row supersedes it when the series domain syncs.
     */
    private suspend fun seriesEnsureExists(series: BookSeriesPayload) {
        val existing = database.seriesDao().getById(series.id)
        if (existing != null) return
        val now = Timestamp(currentEpochMilliseconds())
        database.seriesDao().upsert(
            SeriesEntity(
                id = SeriesId(series.id),
                name = series.name,
                description = null,
                revision = 0,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
