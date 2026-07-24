package com.calypsan.listenup.client.domain.usecase.book

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookEditData
import com.calypsan.listenup.client.domain.model.BookMetadata
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.EditableContributor
import com.calypsan.listenup.client.domain.model.EditableGenre
import com.calypsan.listenup.client.domain.model.EditableMood
import com.calypsan.listenup.client.domain.model.EditableSeries
import com.calypsan.listenup.client.domain.model.EditableTag
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.api.result.notFoundError
import kotlinx.coroutines.flow.first

private val logger = KotlinLogging.logger {}

/**
 * Loads a book and all related data for editing.
 *
 * Transforms domain models to editable formats and fetches
 * all picker data (genres, tags) in a single operation.
 *
 * The ViewModel receives a complete [BookEditData] ready for UI binding,
 * without needing to know about the underlying repositories or transformations.
 *
 * Usage:
 * ```kotlin
 * when (val result = loadBookForEditUseCase(bookId)) {
 *     is AppResult.Success -> initializeEditScreen(result.data)
 *     is AppResult.Failure -> showError(result.message)
 * }
 * ```
 */
open class LoadBookForEditUseCase(
    private val bookRepository: BookRepository,
    private val genreRepository: GenreRepository,
    private val tagRepository: TagRepository,
    private val moodRepository: MoodRepository,
) {
    /**
     * Load a book and all related data for editing.
     *
     * @param bookId The ID of the book to load
     * @return Result containing [BookEditData] on success, or an error if book not found
     */
    open suspend operator fun invoke(bookId: String): AppResult<BookEditData> {
        logger.debug { "Loading book for edit: $bookId" }

        val book = bookRepository.getBookDetail(bookId)
        if (book == null) {
            logger.warn { "Book not found: $bookId" }
            return notFoundError("Book not found")
        }

        return suspendRunCatching {
            // Load all data in parallel conceptually (though suspend functions are sequential)
            val allGenres = loadAllGenres()
            val bookGenres = loadBookGenres(bookId)
            val allTags = loadAllTags()
            val bookTags = loadBookTags(bookId)
            val allMoods = loadAllMoods()
            val bookMoods = loadBookMoods(bookId)

            // Transform to editable format
            val editData =
                BookEditData(
                    bookId = bookId,
                    metadata = book.toMetadata(),
                    contributors = book.toEditableContributors(),
                    series = book.toEditableSeries(),
                    genres = bookGenres,
                    tags = bookTags,
                    moods = bookMoods,
                    allGenres = allGenres,
                    allTags = allTags,
                    allMoods = allMoods,
                    coverPath = book.coverPath,
                    coverHash = book.coverHash,
                )

            logger.debug {
                "Loaded book for edit: ${book.title}, " +
                    "${editData.contributors.size} contributors, " +
                    "${editData.series.size} series, " +
                    "${editData.genres.size} genres, " +
                    "${editData.tags.size} tags, " +
                    "${editData.moods.size} moods"
            }

            editData
        }
    }

    private suspend fun loadAllGenres(): List<EditableGenre> =
        try {
            genreRepository.getAll().map { it.toEditable() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to load all genres" }
            emptyList()
        }

    private suspend fun loadBookGenres(bookId: String): List<EditableGenre> =
        try {
            genreRepository.getGenresForBook(bookId).map { it.toEditable() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to load book genres" }
            emptyList()
        }

    private suspend fun loadAllTags(): List<EditableTag> =
        try {
            tagRepository.observeAllTags().first().map { it.toEditable() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to load all tags" }
            emptyList()
        }

    private suspend fun loadBookTags(bookId: String): List<EditableTag> =
        try {
            tagRepository.observeTagsForBook(bookId).first().map { it.toEditable() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to load book tags" }
            emptyList()
        }

    private suspend fun loadAllMoods(): List<EditableMood> =
        try {
            moodRepository.observeAllMoods().first().map { it.toEditable() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to load all moods" }
            emptyList()
        }

    private suspend fun loadBookMoods(bookId: String): List<EditableMood> =
        try {
            moodRepository.observeMoodsForBook(bookId).first().map { it.toEditable() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to load book moods" }
            emptyList()
        }

    private fun BookDetail.toMetadata(): BookMetadata =
        BookMetadata(
            title = title,
            sortTitle = sortTitle ?: generateSortTitle(title),
            subtitle = subtitle ?: "",
            description = description ?: "",
            publishYear = publishYear?.toString() ?: "",
            publisher = publisher ?: "",
            language = language,
            isbn = isbn ?: "",
            asin = asin ?: "",
            abridged = abridged,
            addedAt = addedAt.epochMillis,
        )

    private fun BookDetail.toEditableContributors(): List<EditableContributor> =
        allContributors.map { contributor ->
            EditableContributor(
                id = contributor.id,
                name = contributor.name,
                roles =
                    contributor.roles
                        .mapNotNull { ContributorRole.fromApiValue(it) }
                        .toSet(),
                creditedAs = contributor.creditedAs,
            )
        }

    private fun BookDetail.toEditableSeries(): List<EditableSeries> =
        series.map { s ->
            EditableSeries(
                id = s.seriesId,
                name = s.seriesName,
                sequence = s.sequence,
            )
        }

    private fun Genre.toEditable(): EditableGenre =
        EditableGenre(
            id = id,
            name = name,
            path = path,
        )

    private fun Tag.toEditable(): EditableTag =
        EditableTag(
            id = id,
            slug = slug,
        )

    private fun Mood.toEditable(): EditableMood =
        EditableMood(
            id = id,
            slug = slug,
        )
}

private val leadingArticles = listOf("The ", "A ", "An ")

/**
 * Generate a sort-friendly title by moving leading articles to the end.
 *
 * Examples:
 * - "The Lord of the Rings" → "Lord of the Rings, The"
 * - "A Brief History of Time" → "Brief History of Time, A"
 * - "An Introduction to Physics" → "Introduction to Physics, An"
 * - "Mistborn" → "Mistborn" (no change)
 */
private fun generateSortTitle(title: String): String {
    for (article in leadingArticles) {
        if (title.startsWith(article, ignoreCase = true)) {
            val matchedArticle = title.substring(0, article.length).trimEnd()
            val remainder = title.substring(article.length)
            return "$remainder, $matchedArticle"
        }
    }
    return title
}
