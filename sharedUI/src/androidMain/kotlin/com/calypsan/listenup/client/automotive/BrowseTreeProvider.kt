package com.calypsan.listenup.client.automotive

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.first

private val logger = KotlinLogging.logger {}

private const val MAX_ITEMS_PER_LEVEL = 8

/**
 * Provides browse tree data for Android Auto.
 *
 * Builds [MediaItem] lists for each browse node, enabling users to
 * navigate their audiobook library from the car head unit.
 *
 * Browse tree structure:
 * - Resume: Single playable item for the most recent in-progress book
 * - Library: Browsable folder containing Recent, Downloaded, Series, Authors
 * - Collections: User's custom collections (if any)
 * - Bookmarks: Saved positions (if any)
 */
class BrowseTreeProvider(
    private val homeRepository: HomeRepository,
    private val bookRepository: BookRepository,
    private val seriesRepository: SeriesRepository,
    private val contributorRepository: ContributorRepository,
    private val downloadRepository: DownloadRepository,
    private val imageStorage: ImageStorage,
) {
    /**
     * Get the root media item.
     */
    fun getRoot(): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(BrowseTree.ROOT)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle("ListenUp")
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            ).build()

    /**
     * Get children for a browse node.
     *
     * @param parentId The media ID of the parent node
     * @return List of child media items
     */
    suspend fun getChildren(parentId: String): List<MediaItem> {
        logger.debug { "getChildren: parentId=$parentId" }

        return when (parentId) {
            BrowseTree.ROOT -> getRootChildren()
            BrowseTree.LIBRARY -> getLibraryChildren()
            BrowseTree.LIBRARY_RECENT -> getRecentBooks()
            BrowseTree.LIBRARY_DOWNLOADED -> getDownloadedBooks()
            BrowseTree.LIBRARY_SERIES -> getSeriesList()
            BrowseTree.LIBRARY_AUTHORS -> getAuthorsList()
            else -> getDynamicChildren(parentId)
        }
    }

    /**
     * Get a specific media item by ID.
     */
    suspend fun getItem(mediaId: String): MediaItem? {
        logger.debug { "getItem: mediaId=$mediaId" }

        // Handle book items
        BrowseTree.extractBookId(mediaId)?.let { bookId ->
            return getBookItem(bookId)
        }

        // Handle static nodes
        return when (mediaId) {
            BrowseTree.ROOT -> {
                getRoot()
            }

            BrowseTree.RESUME -> {
                getResumeItem()
            }

            BrowseTree.LIBRARY -> {
                createBrowsableItem(
                    BrowseTree.LIBRARY,
                    "Library",
                    MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
                )
            }

            else -> {
                null
            }
        }
    }

    // ========== Root Level ==========

    private suspend fun getRootChildren(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        // 1. Resume item (if there's an in-progress book)
        getResumeItem()?.let { items.add(it) }

        // 2. Library folder
        items.add(
            createBrowsableItem(
                mediaId = BrowseTree.LIBRARY,
                title = "Library",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
            ),
        )

        // TODO: Add Collections and Bookmarks when implemented

        return items
    }

    private suspend fun getResumeItem(): MediaItem? {
        val result = homeRepository.getContinueListening(1)
        if (result !is AppResult.Success || result.data.isEmpty()) {
            return null
        }

        val book = result.data.first()
        return createPlayableBookItem(book)
    }

    // ========== Library Level ==========

    private fun getLibraryChildren(): List<MediaItem> =
        listOf(
            createBrowsableItem(
                mediaId = BrowseTree.LIBRARY_RECENT,
                title = "Recently Played",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
            ),
            createBrowsableItem(
                mediaId = BrowseTree.LIBRARY_DOWNLOADED,
                title = "Downloaded",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
            ),
            createBrowsableItem(
                mediaId = BrowseTree.LIBRARY_SERIES,
                title = "By Series",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
            ),
            createBrowsableItem(
                mediaId = BrowseTree.LIBRARY_AUTHORS,
                title = "By Author",
                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            ),
        )

    private suspend fun getRecentBooks(): List<MediaItem> {
        val result = homeRepository.getContinueListening(MAX_ITEMS_PER_LEVEL)
        if (result !is AppResult.Success) {
            return emptyList()
        }

        return result.data.map { book -> createPlayableBookItem(book) }
    }

    private suspend fun getDownloadedBooks(): List<MediaItem> =
        downloadRepository
            .observeDownloadedBooks()
            .first()
            .take(MAX_ITEMS_PER_LEVEL)
            .map { book -> createBookMediaItem(bookId = book.bookId, title = book.title, subtitle = null) }

    private suspend fun getSeriesList(): List<MediaItem> =
        seriesRepository
            .observeAll()
            .first()
            .take(MAX_ITEMS_PER_LEVEL)
            .map { series ->
                createBrowsableItem(
                    mediaId = BrowseTree.seriesId(series.id.value),
                    title = series.name,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
                )
            }

    private suspend fun getAuthorsList(): List<MediaItem> =
        contributorRepository
            .observeAll()
            .first()
            .take(MAX_ITEMS_PER_LEVEL)
            .map { author ->
                createBrowsableItem(
                    mediaId = BrowseTree.authorId(author.id.value),
                    title = author.name,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
                )
            }

    // ========== Dynamic Nodes ==========

    private suspend fun getDynamicChildren(parentId: String): List<MediaItem> {
        // Series books
        BrowseTree.extractSeriesId(parentId)?.let { seriesId ->
            return getBooksInSeries(seriesId)
        }

        // Author books
        BrowseTree.extractAuthorId(parentId)?.let { authorId ->
            return getBooksByAuthor(authorId)
        }

        return emptyList()
    }

    private suspend fun getBooksInSeries(seriesId: String): List<MediaItem> {
        val series = seriesRepository.observeSeriesWithBooks(seriesId).first() ?: return emptyList()
        return series.books
            .take(MAX_ITEMS_PER_LEVEL)
            .map { book ->
                createBookMediaItem(bookId = book.id.value, title = book.title, subtitle = series.series.name)
            }
    }

    private suspend fun getBooksByAuthor(authorId: String): List<MediaItem> {
        val bookIds = contributorRepository.getBookIdsForContributor(authorId)
        val authorName = contributorRepository.getById(authorId)?.name

        return bookIds
            .take(MAX_ITEMS_PER_LEVEL)
            .mapNotNull { bookId ->
                val book = bookRepository.getBookListItem(bookId) ?: return@mapNotNull null
                createBookMediaItem(bookId = book.id.value, title = book.title, subtitle = authorName)
            }
    }

    /**
     * Get a playable MediaItem for a specific book.
     *
     * Used by voice search to return search results.
     */
    suspend fun getBookItem(bookId: String): MediaItem? {
        val book = bookRepository.getBookListItem(bookId) ?: return null
        return createBookMediaItem(bookId = book.id.value, title = book.title, subtitle = null)
    }

    // ========== Item Builders ==========

    private fun createBrowsableItem(
        mediaId: String,
        title: String,
        mediaType: Int,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .build(),
            ).build()

    private fun createPlayableBookItem(book: ContinueListeningBook): MediaItem {
        val artworkUri = book.coverPath?.let { Uri.parse("file://$it") }

        return MediaItem
            .Builder()
            .setMediaId(BrowseTree.bookId(book.bookId))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(book.title)
                    .setSubtitle("${book.authorNames} - ${book.timeRemainingFormatted}")
                    .setArtist(book.authorNames)
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build(),
            ).build()
    }

    private fun createBookMediaItem(
        bookId: String,
        title: String,
        subtitle: String?,
    ): MediaItem {
        val coverPath = imageStorage.getCoverPath(BookId(bookId))
        val artworkUri =
            if (imageStorage.exists(BookId(bookId))) {
                Uri.parse("file://$coverPath")
            } else {
                null
            }

        return MediaItem
            .Builder()
            .setMediaId(BrowseTree.bookId(bookId))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build(),
            ).build()
    }
}
