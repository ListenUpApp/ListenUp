package com.calypsan.listenup.client.domain.usecase.book

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.model.BookOriginalState
import com.calypsan.listenup.client.domain.model.BookUpdateRequest
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Saves book changes, only updating what actually changed.
 *
 * Compares current state against original to determine deltas.
 * Orchestrates updates in correct order with fail-fast error handling.
 *
 * This use case encapsulates all the business logic that was previously
 * spread across BookEditViewModel's saveChanges() method (~190 lines).
 *
 * Usage:
 * ```kotlin
 * when (val result = updateBookUseCase(current, original)) {
 *     is AppResult.Success -> navigateBack()
 *     is AppResult.Failure -> showError(result.message)
 * }
 * ```
 */
open class UpdateBookUseCase(
    private val bookEditRepository: BookEditRepository,
    private val tagRepository: TagRepository,
    private val moodRepository: MoodRepository,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
) {
    /**
     * Save book changes.
     *
     * Compares current state against original to determine what changed,
     * then applies only the necessary updates. Fails fast on first error.
     *
     * @param current The current state to save
     * @param original The original state when editing began
     * @return Result indicating success or first failure encountered
     */
    open suspend operator fun invoke(
        current: BookUpdateRequest,
        original: BookOriginalState,
    ): AppResult<Unit> {
        val changes = detectChanges(current, original)

        if (!changes.hasAnyChanges) {
            logger.debug { "No changes detected for book ${current.bookId}" }
            return AppResult.Success(Unit)
        }

        logger.info {
            "Saving book changes: ${changes.summary()}"
        }

        if (changes.metadataChanged) {
            when (val result = updateMetadata(current)) {
                is AppResult.Success -> {}

                is AppResult.Failure -> {
                    return result
                }
            }
        }

        if (changes.contributorsChanged) {
            when (val result = updateContributors(current)) {
                is AppResult.Success -> {}

                is AppResult.Failure -> {
                    return result
                }
            }
        }

        if (changes.seriesChanged) {
            when (val result = updateSeries(current)) {
                is AppResult.Success -> {}

                is AppResult.Failure -> {
                    return result
                }
            }
        }

        if (changes.genresChanged) {
            when (val result = updateGenres(current)) {
                is AppResult.Success -> {}

                is AppResult.Failure -> {
                    return result
                }
            }
        }

        return suspendRunCatching {
            if (changes.tagsChanged) updateTags(current, original)
            if (changes.moodsChanged) updateMoods(current, original)
            if (changes.coverChanged) commitAndUploadCover(current)
            logger.info { "Book ${current.bookId} saved successfully" }
        }
    }

    /**
     * Detect which parts of the book have changed.
     */
    private fun detectChanges(
        current: BookUpdateRequest,
        original: BookOriginalState,
    ): BookChanges =
        BookChanges(
            metadataChanged = current.metadata != original.metadata,
            contributorsChanged = current.contributors != original.contributors,
            seriesChanged = current.series != original.series,
            genresChanged = current.genres != original.genres,
            tagsChanged = current.tags != original.tags,
            moodsChanged = current.moods != original.moods,
            coverChanged = current.pendingCover != null,
        )

    private suspend fun updateMetadata(current: BookUpdateRequest): AppResult<Unit> {
        logger.debug { "Updating metadata for book ${current.bookId}" }

        val metadata = current.metadata
        val patch =
            BookUpdate(
                title = metadata.title,
                sortTitle = metadata.sortTitle.ifBlank { null },
                subtitle = metadata.subtitle.ifBlank { null },
                description = metadata.description.ifBlank { null },
                publisher = metadata.publisher.ifBlank { null },
                publishYear = metadata.publishYear.ifBlank { null }?.toIntOrNull(),
                language = metadata.language,
                isbn = metadata.isbn.ifBlank { null },
                asin = metadata.asin.ifBlank { null },
                abridged = metadata.abridged,
                addedAt = metadata.addedAt,
            )
        return bookEditRepository.updateBook(BookId(current.bookId), patch)
    }

    private suspend fun updateContributors(current: BookUpdateRequest): AppResult<Unit> {
        logger.debug { "Updating contributors for book ${current.bookId}" }

        // Wire DTO is one row per (contributor, role); editable model holds a Set<ContributorRole>.
        // Flatten, assigning sequential positions for stable ordering on the server.
        val contributorInputs =
            current.contributors
                .flatMap { editable ->
                    editable.roles.map { role -> editable to role }
                }.mapIndexed { index, (editable, role) ->
                    BookContributorInput(
                        id = editable.id?.let { ContributorId(it) },
                        name = editable.name,
                        role = role.apiValue,
                        creditedAs = editable.creditedAs,
                        position = index,
                    )
                }

        return bookEditRepository.setBookContributors(BookId(current.bookId), contributorInputs)
    }

    private suspend fun updateSeries(current: BookUpdateRequest): AppResult<Unit> {
        logger.debug { "Updating series for book ${current.bookId}" }

        val seriesInputs =
            current.series.map { editable ->
                BookSeriesInput(
                    id = editable.id?.let { SeriesId(it) },
                    name = editable.name,
                    position = editable.sequence?.toDoubleOrNull(),
                )
            }

        return bookEditRepository.setBookSeries(BookId(current.bookId), seriesInputs)
    }

    private suspend fun updateGenres(current: BookUpdateRequest): AppResult<Unit> {
        logger.debug { "Updating genres for book ${current.bookId}" }

        val inputs = current.genres.map { BookGenreInput(genreId = GenreId(it.id)) }
        return bookEditRepository.setBookGenres(BookId(current.bookId), inputs)
    }

    private suspend fun updateTags(
        current: BookUpdateRequest,
        original: BookOriginalState,
    ) {
        logger.debug { "Updating tags for book ${current.bookId}" }

        val currentSlugs = current.tags.map { it.slug }.toSet()
        val originalSlugs = original.tags.map { it.slug }.toSet()

        // Remove deleted tags
        val removedSlugs = originalSlugs - currentSlugs
        for (slug in removedSlugs) {
            val tagId = original.tags.find { it.slug == slug }?.id ?: continue
            when (val result = tagRepository.removeTagFromBook(current.bookId, slug, tagId)) {
                is AppResult.Success -> { /* ok */ }

                is AppResult.Failure -> {
                    logger.warn { "Failed to remove tag '$slug' from book ${current.bookId}: ${result.error.message}" }
                }
                // Continue with other tags - tag removal is non-critical
            }
        }

        // Add new tags
        val addedSlugs = currentSlugs - originalSlugs
        for (slug in addedSlugs) {
            when (val result = tagRepository.addTagToBook(current.bookId, slug)) {
                is AppResult.Success -> { /* ok */ }

                is AppResult.Failure -> {
                    logger.warn { "Failed to add tag '$slug' to book ${current.bookId}: ${result.error.message}" }
                }
                // Continue with other tags - tag addition is non-critical
            }
        }

        logger.debug { "Tags updated: +${addedSlugs.size}, -${removedSlugs.size}" }
    }

    private suspend fun updateMoods(
        current: BookUpdateRequest,
        original: BookOriginalState,
    ) {
        logger.debug { "Updating moods for book ${current.bookId}" }

        val currentSlugs = current.moods.map { it.slug }.toSet()
        val originalSlugs = original.moods.map { it.slug }.toSet()

        // Remove deleted moods
        val removedSlugs = originalSlugs - currentSlugs
        for (slug in removedSlugs) {
            val moodId = original.moods.find { it.slug == slug }?.id ?: continue
            when (val result = moodRepository.removeMoodFromBook(current.bookId, moodId)) {
                is AppResult.Success -> { /* ok */ }

                is AppResult.Failure -> {
                    logger.warn { "Failed to remove mood '$slug' from book ${current.bookId}: ${result.error.message}" }
                }
                // Continue with other moods - mood removal is non-critical
            }
        }

        // Add new moods
        val addedSlugs = currentSlugs - originalSlugs
        for (slug in addedSlugs) {
            when (val result = moodRepository.addMoodToBook(current.bookId, slug)) {
                is AppResult.Success -> { /* ok */ }

                is AppResult.Failure -> {
                    logger.warn { "Failed to add mood '$slug' to book ${current.bookId}: ${result.error.message}" }
                }
                // Continue with other moods - mood addition is non-critical
            }
        }

        logger.debug { "Moods updated: +${addedSlugs.size}, -${removedSlugs.size}" }
    }

    private suspend fun commitAndUploadCover(current: BookUpdateRequest) {
        val pendingCover = current.pendingCover ?: return
        val bookId = BookId(current.bookId)

        logger.debug { "Committing and uploading cover for book ${current.bookId}" }

        // Commit staging to main location
        when (val commitResult = imageStagingRepository.commitBookCoverStaging(bookId)) {
            is AppResult.Success -> logger.debug { "Staging cover committed to main location" }
            is AppResult.Failure -> logger.error { "Failed to commit staging cover: ${commitResult.message}" }
        }

        // Upload to server (best-effort - local cover is already saved)
        when (
            val uploadResult =
                imageRepository.uploadBookCover(
                    bookId = current.bookId,
                    imageData = pendingCover.data,
                    filename = pendingCover.filename,
                )
        ) {
            is AppResult.Success -> {
                logger.info { "Cover uploaded to server" }
            }

            is AppResult.Failure -> {
                logger.warn { "Failed to upload cover to server: ${uploadResult.message}" }
                // Don't fail the save - local cover is saved, server sync can happen later
            }
        }
    }

    /**
     * Internal data class tracking which parts of the book changed.
     */
    private data class BookChanges(
        val metadataChanged: Boolean,
        val contributorsChanged: Boolean,
        val seriesChanged: Boolean,
        val genresChanged: Boolean,
        val tagsChanged: Boolean,
        val moodsChanged: Boolean,
        val coverChanged: Boolean,
    ) {
        val hasAnyChanges: Boolean
            get() =
                metadataChanged || contributorsChanged || seriesChanged ||
                    genresChanged || tagsChanged || moodsChanged || coverChanged

        fun summary(): String =
            buildList {
                if (metadataChanged) add("metadata")
                if (contributorsChanged) add("contributors")
                if (seriesChanged) add("series")
                if (genresChanged) add("genres")
                if (tagsChanged) add("tags")
                if (moodsChanged) add("moods")
                if (coverChanged) add("cover")
            }.joinToString(", ")
    }
}
