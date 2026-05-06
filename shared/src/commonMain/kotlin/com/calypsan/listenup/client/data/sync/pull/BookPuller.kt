package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.BookTagCrossRef
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.BookResponse
import com.calypsan.listenup.client.data.remote.model.SyncBooksResponse
import com.calypsan.listenup.client.data.remote.model.toEntity
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.data.sync.conflict.ConflictDetectorContract
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import com.calypsan.listenup.client.core.error.AppException
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

/**
 * Handles paginated book fetching, processing, and relationship syncing.
 */
class BookPuller(
    private val transactionRunner: TransactionRunner,
    private val syncApi: SyncApiContract,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val genreDao: GenreDao,
    private val bookRelationshipWriter: BookRelationshipWriter,
    private val conflictDetector: ConflictDetectorContract,
    private val imageDownloader: ImageDownloaderContract,
    private val coverDownloadDao: com.calypsan.listenup.client.data.local.db.CoverDownloadDao,
) : Puller {
    /**
     * Pull all books from server with pagination.
     *
     * @param updatedAfter ISO timestamp for delta sync, null for full sync
     * @param onProgress Callback for progress updates
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ) {
        var cursor: String? = null
        var hasMore = true
        val limit = 100
        var itemsSynced = 0

        while (hasMore) {
            when (val result = syncApi.getBooks(limit = limit, cursor = cursor, updatedAfter = updatedAfter)) {
                is Success -> {
                    val response = result.data
                    val serverBooks = response.books.map { it.toEntity() }
                    val deletedBookIds = response.deletedBookIds
                    itemsSynced += serverBooks.size + deletedBookIds.size

                    onProgress(
                        SyncStatus.Progress(
                            phase = SyncPhase.SYNCING_BOOKS,
                            phaseItemsSynced = itemsSynced,
                            phaseTotalItems = -1,
                            message = "Syncing books: $itemsSynced synced...",
                        ),
                    )

                    logger.debug {
                        "Fetched batch: ${serverBooks.size} books, ${deletedBookIds.size} deletions"
                    }

                    // Handle deletions
                    if (deletedBookIds.isNotEmpty()) {
                        bookDao.deleteByIds(deletedBookIds.map { BookId(it) })
                        logger.info { "Removed ${deletedBookIds.size} books deleted on server" }
                    }

                    if (serverBooks.isNotEmpty()) {
                        processServerBooks(serverBooks, response)
                    }

                    // Advance cursor only after all books in this page are persisted.
                    // Moving this after persistence ensures an interrupted sync does not
                    // skip books: the next delta sync will re-fetch the missing page.
                    cursor = response.nextCursor
                    hasMore = response.hasMore
                }

                is Failure -> {
                    throw AppException(result.error)
                }
            }
        }

        logger.info { "Books sync complete: $itemsSynced items processed" }
    }

    /**
     * Process server books: detect conflicts, upsert, sync related entities.
     *
     * All DB writes for a page — conflict marks, book upsert, chapters, per-book junction
     * replacements via [BookRelationshipWriter], cover-download tasks — commit atomically.
     * If any step throws, Room rolls the transaction back so the DB never holds a half-synced
     * book (Finding 05 D2).
     *
     * Pure collection (bundles, tag catalog, genre name→id resolution) and disk I/O run
     * outside the transaction so the writer connection is held only long enough to apply
     * the actual mutations.
     */
    private suspend fun processServerBooks(
        serverBooks: List<BookEntity>,
        response: SyncBooksResponse,
    ) {
        logger.info { "processServerBooks: received ${serverBooks.size} books from server" }

        val conflicts = conflictDetector.detectBookConflicts(serverBooks)
        conflicts.forEach { (bookId, _) ->
            logger.warn { "Conflict detected for book $bookId - server version is newer" }
        }

        val booksToUpsert = serverBooks.filterNot { conflictDetector.shouldPreserveLocalChanges(it) }
        logger.info { "processServerBooks: ${booksToUpsert.size} books to upsert (after filtering)" }

        if (booksToUpsert.isEmpty()) {
            logger.info { "processServerBooks: no books to upsert, skipping cover downloads" }
            return
        }

        invalidateChangedCovers(booksToUpsert)

        val booksWithColors = preserveLocalColors(booksToUpsert)

        // Pure-collection phase — no DB writes, reads only. Held outside the transaction.
        // Pair each book with its response once, enforcing the invariant that every booksToUpsert
        // entry has a matching response (they are derived from the same response.books list).
        val responseById = response.books.associateBy { it.id }
        val pairedBooks: List<Pair<BookEntity, BookResponse>> =
            booksToUpsert.map { book ->
                val bookResponse =
                    requireNotNull(responseById[book.id.value]) {
                        "invariant: booksToUpsert entry ${book.id.value} has no matching response"
                    }
                book to bookResponse
            }
        val chaptersToUpsert = collectChapters(pairedBooks)
        val tagCatalog = collectTagCatalog(pairedBooks)
        val genreNameToId = resolveGenreNameToId(pairedBooks)
        val bundles = collectBundles(pairedBooks, genreNameToId)

        transactionRunner.atomically {
            conflicts.forEach { (bookId, serverVersion) ->
                bookDao.markConflict(bookId, serverVersion)
            }

            bookDao.upsertAll(booksWithColors)

            if (chaptersToUpsert.isNotEmpty()) {
                chapterDao.upsertAll(chaptersToUpsert)
            }

            bookRelationshipWriter.replaceAll(bundles, tagCatalog)

            enqueueCoverDownloads(booksToUpsert)
        }
    }

    /**
     * Preserve local-only palette colors (dominantColor, darkMutedColor, vibrantColor)
     * that would be wiped by upsert since the server doesn't send them.
     */
    private suspend fun preserveLocalColors(books: List<BookEntity>): List<BookEntity> {
        val existingColors =
            books
                .mapNotNull { book ->
                    bookDao.getById(book.id)?.let { existing ->
                        book.id to Triple(existing.dominantColor, existing.darkMutedColor, existing.vibrantColor)
                    }
                }.toMap()

        return books.map { book ->
            val colors = existingColors[book.id]
            if (colors != null && book.dominantColor == null && book.darkMutedColor == null &&
                book.vibrantColor == null
            ) {
                book.copy(
                    dominantColor = colors.first,
                    darkMutedColor = colors.second,
                    vibrantColor = colors.third,
                )
            } else {
                book
            }
        }
    }

    /**
     * Detect books whose cover URL has changed and delete their local cached covers.
     *
     * This ensures that when metadata is updated with a new cover, the new cover
     * will be downloaded instead of using the stale cached version.
     */
    private suspend fun invalidateChangedCovers(serverBooks: List<BookEntity>) {
        serverBooks.forEach { serverBook ->
            val localBook = bookDao.getById(serverBook.id)
            if (localBook != null && localBook.coverUrl != serverBook.coverUrl) {
                logger.info {
                    "Cover changed for book ${serverBook.id.value}: " +
                        "'${localBook.coverUrl}' -> '${serverBook.coverUrl}'"
                }
                imageDownloader.deleteCover(serverBook.id)
            }
        }
    }

    private fun collectChapters(pairedBooks: List<Pair<BookEntity, BookResponse>>): List<ChapterEntity> =
        pairedBooks.flatMap { (book, bookResponse) ->
            bookResponse.chapters.mapIndexed { index, chapter ->
                chapter.toEntity(book.id, index)
            }
        }

    private fun collectTagCatalog(pairedBooks: List<Pair<BookEntity, BookResponse>>): List<TagEntity> =
        pairedBooks
            .flatMap { (_, bookResponse) -> bookResponse.tags }
            .distinctBy { it.id }
            .map { tag ->
                TagEntity(
                    id = tag.id,
                    slug = tag.slug,
                    bookCount = tag.bookCount,
                    createdAt = Timestamp.now(),
                )
            }

    /**
     * Resolve genre names (server-side representation) to local catalog IDs. Genres not
     * present in the local catalog are logged and silently dropped from the cross-refs —
     * next full sync populates them. Runs outside the transaction; the genre catalog is
     * immutable within a pull cycle (no concurrent writer), so this read is race-free.
     */
    private suspend fun resolveGenreNameToId(pairedBooks: List<Pair<BookEntity, BookResponse>>): Map<String, String> {
        val allNames =
            pairedBooks
                .flatMap { (_, bookResponse) -> bookResponse.genres.orEmpty() }
                .distinctBy { it.lowercase() }

        if (allNames.isEmpty()) return emptyMap()

        return genreDao.getIdsByNames(allNames).associate { it.name.lowercase() to it.id }
    }

    private fun collectBundles(
        pairedBooks: List<Pair<BookEntity, BookResponse>>,
        genreNameToId: Map<String, String>,
    ): List<BookRelationshipBundle> =
        pairedBooks.map { (book, bookResponse) ->
            val contributors =
                bookResponse.contributors.flatMap { contributor ->
                    contributor.roles.map { role -> contributor.toEntity(book.id, role) }
                }

            val series = bookResponse.seriesInfo.map { it.toEntity(book.id) }

            val tags =
                bookResponse.tags.map {
                    BookTagCrossRef(
                        bookId = book.id,
                        tagId = it.id,
                    )
                }

            val genres =
                bookResponse.genres.orEmpty().mapNotNull { name ->
                    val id = genreNameToId[name.lowercase()]
                    if (id == null) {
                        logger.warn { "Genre '$name' not in catalog; skipping for book ${book.id.value}" }
                        null
                    } else {
                        BookGenreCrossRef(bookId = book.id, genreId = id)
                    }
                }

            val audioFiles =
                bookResponse.audioFiles.mapIndexed { idx, af ->
                    AudioFileEntity(
                        bookId = book.id,
                        index = idx,
                        id = af.id,
                        filename = af.filename,
                        format = af.format,
                        codec = af.codec,
                        duration = af.duration,
                        size = af.size,
                    )
                }

            BookRelationshipBundle(
                bookId = book.id,
                contributors = contributors,
                series = series,
                tags = tags,
                genres = genres,
                audioFiles = audioFiles,
            )
        }

    /**
     * Enqueue cover downloads for the given books into the persistent queue.
     *
     * Replaces the old fire-and-forget scope.launch approach. Tasks are persisted
     * in Room and processed by CoverDownloadWorker, surviving app backgrounding
     * and crashes.
     */
    private suspend fun enqueueCoverDownloads(books: List<BookEntity>) {
        val tasks =
            books.map { book ->
                com.calypsan.listenup.client.data.local.db
                    .CoverDownloadTaskEntity(bookId = book.id)
            }

        if (tasks.isEmpty()) return

        coverDownloadDao.enqueueAll(tasks)
        logger.info { "Enqueued ${tasks.size} cover downloads to persistent queue" }
    }
}
