package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.toDetail
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.TagRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository for book data operations.
 *
 * Implements offline-first pattern:
 * - UI observes Room database via Flow
 * - Writes update local database immediately
 * - Sync happens in background
 *
 * Single source of truth: Room database
 *
 * Transforms data layer (BookEntity) to domain layer (Book) for UI consumption.
 * Uses ImageStorage to resolve local cover file paths.
 *
 * Uses Room Relations to efficiently batch-load books with their contributors,
 * avoiding N+1 query problems when loading book lists.
 *
 * @property bookDao Room DAO for book operations
 * @property chapterDao Room DAO for chapter operations
 * @property audioFileDao Room DAO for audio-file operations
 * @property transactionRunner Runs multi-table writes atomically
 * @property imageStorage Storage for resolving cover image paths
 * @property genreRepository Upstream Flow source for a book's genres, composed
 *   into [observeBookDetail] so genre edits propagate to detail-screen consumers.
 * @property tagRepository Upstream Flow source for a book's tags, composed
 *   into [observeBookDetail] so tag edits propagate to detail-screen consumers.
 */
class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val audioFileDao: AudioFileDao,
    private val transactionRunner: TransactionRunner,
    private val imageStorage: ImageStorage,
    private val genreRepository: GenreRepository,
    private val tagRepository: TagRepository,
) : com.calypsan.listenup.client.domain.repository.BookRepository {
    private val logger = KotlinLogging.logger {}

    /**
     * Trigger sync to refresh books from server.
     *
     * @return Result indicating sync success or failure
     */
    override suspend fun refreshBooks(): AppResult<Unit> {
        logger.debug { "Refreshing books from server" }
        return Success(Unit)
    }

    /**
     * Get chapters for a book from the local database.
     *
     * @param bookId The book ID
     * @return List of chapters
     */
    override suspend fun getChapters(bookId: String): List<Chapter> {
        val localChapters = chapterDao.getChaptersForBook(BookId(bookId))
        return localChapters.map { it.toDomain() }
    }

    private fun ChapterEntity.toDomain(): Chapter =
        Chapter(
            id = id.value,
            title = title,
            duration = duration,
            startTime = startTime,
        )

    /**
     * Observe random unstarted books for discovery.
     *
     * Transforms data layer entities to domain DiscoveryBook models,
     * resolving local cover paths via ImageStorage.
     */
    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryBook>> =
        bookDao.observeRandomUnstartedBooksWithAuthor(limit).map { entities ->
            entities.map { entity ->
                entity.toDiscoveryBook(imageStorage)
            }
        }

    /**
     * Observe recently added books for discovery.
     *
     * Transforms data layer entities to domain DiscoveryBook models,
     * resolving local cover paths via ImageStorage.
     */
    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> =
        bookDao.observeRecentlyAddedWithAuthor(limit).map { entities ->
            entities.map { entity ->
                entity.toDiscoveryBook(imageStorage)
            }
        }

    override fun observeBookListItems(): Flow<List<BookListItem>> =
        bookDao.observeAllWithContributors().map { rows ->
            rows.map { it.toListItem(imageStorage) }
        }

    override suspend fun getBookListItem(id: String): BookListItem? =
        bookDao.getByIdWithContributors(BookId(id))?.toListItem(imageStorage)

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> {
        if (ids.isEmpty()) return emptyList()
        return bookDao
            .getByIdsWithContributors(ids.map { BookId(it) })
            .map { it.toListItem(imageStorage) }
    }

    override fun observeBookDetail(id: String): Flow<BookDetail?> {
        val bookId = BookId(id)
        return combine(
            bookDao.observeByIdWithContributors(bookId),
            genreRepository.observeGenresForBook(id),
            tagRepository.observeTagsForBook(id),
        ) { row, genres, tags ->
            row?.toDetail(imageStorage, genres, tags)
        }
    }

    override suspend fun getBookDetail(id: String): BookDetail? {
        val bookId = BookId(id)
        val row = bookDao.getByIdWithContributors(bookId) ?: return null
        val genres = genreRepository.observeGenresForBook(id).first()
        val tags = tagRepository.observeTagsForBook(id).first()
        return row.toDetail(imageStorage, genres, tags)
    }

    override suspend fun upsertWithAudioFiles(
        book: BookEntity,
        audioFiles: List<AudioFileEntity>,
    ): AppResult<Unit> =
        suspendRunCatching {
            transactionRunner.atomically {
                bookDao.upsert(book)
                audioFileDao.deleteForBook(book.id.value)
                if (audioFiles.isNotEmpty()) {
                    audioFileDao.upsertAll(audioFiles)
                }
            }
        }
}

/**
 * Convert DiscoveryBookWithAuthor entity to domain DiscoveryBook.
 */
private fun com.calypsan.listenup.client.data.local.db.DiscoveryBookWithAuthor.toDiscoveryBook(
    imageStorage: ImageStorage,
): DiscoveryBook =
    DiscoveryBook(
        id = id.value,
        title = title,
        authorName = authorName,
        coverPath = if (imageStorage.exists(id)) imageStorage.getCoverPath(id) else null,
        coverBlurHash = coverBlurHash,
        createdAt = createdAt.epochMillis,
    )
