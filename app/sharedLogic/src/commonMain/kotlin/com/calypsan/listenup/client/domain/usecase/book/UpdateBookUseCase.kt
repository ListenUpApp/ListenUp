package com.calypsan.listenup.client.domain.usecase.book

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.model.BookOriginalState
import com.calypsan.listenup.client.domain.model.BookUpdateRequest
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.domain.repository.PendingImageUploads
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.usecase.SaveOutcome
import com.calypsan.listenup.client.domain.usecase.settle
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
 *     is AppResult.Success -> {
 *         if (result.data is SaveOutcome.SavedImageNotUploaded) tellUserTheImageDidNotUpload()
 *         navigateBack()
 *     }
 *     is AppResult.Failure -> showError(result.message)
 * }
 * ```
 */
open class UpdateBookUseCase internal constructor(
    private val bookEditRepository: BookEditRepository,
    private val tagRepository: TagRepository,
    private val moodRepository: MoodRepository,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
    private val pendingImageUploads: PendingImageUploads,
) {
    /**
     * Save book changes.
     *
     * Compares current state against original to determine what changed,
     * then applies only the necessary updates. Fails fast on first error.
     *
     * @param current The current state to save
     * @param original The original state when editing began
     * @return The first failure encountered, or a [SaveOutcome] saying whether a new cover reached
     *   the server. A cover upload that fails does not fail the save: the cover is already on this
     *   device, so the result is [SaveOutcome.SavedImageNotUploaded] instead.
     */
    open suspend operator fun invoke(
        current: BookUpdateRequest,
        original: BookOriginalState,
    ): AppResult<SaveOutcome> {
        val changes = detectChanges(current, original)

        if (!changes.hasAnyChanges) {
            logger.debug { "No changes detected for book ${current.bookId}" }
            return AppResult.Success(SaveOutcome.Saved)
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
            val outcome = if (changes.coverChanged) commitAndUploadCover(current) else SaveOutcome.Saved
            logger.info { "Book ${current.bookId} saved successfully" }
            outcome
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
                    // The one parse of what the user typed. This same call used to be a
                    // silent data-loss bug: the field was loaded straight from a free-form stored
                    // string, so opening a book whose sequence was "1-3" and saving ANY edit ran
                    // that label through here, got null, and wiped it with no error. The stored
                    // value is a number now and the field is loaded by formatting it, so the text
                    // arriving here can only be something this parses — or something the user
                    // typed over it, which is theirs to correct.
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

    private suspend fun commitAndUploadCover(current: BookUpdateRequest): SaveOutcome {
        val pendingCover = current.pendingCover ?: return SaveOutcome.Saved
        val bookId = BookId(current.bookId)

        logger.debug { "Committing and uploading cover for book ${current.bookId}" }

        // Commit staging to main location
        when (val commitResult = imageStagingRepository.commitBookCoverStaging(bookId)) {
            is AppResult.Success -> logger.debug { "Staging cover committed to main location" }
            is AppResult.Failure -> logger.error { "Failed to commit staging cover: ${commitResult.message}" }
        }

        // The local cover is already saved, so a failed upload does not fail the save. It is queued
        // to retry when it can succeed later, and reported in the outcome so the screen can say the
        // other devices will not see it yet.
        val uploadResult =
            imageRepository.uploadBookCover(
                bookId = current.bookId,
                imageData = pendingCover.data,
                filename = pendingCover.filename,
            )
        when (uploadResult) {
            is AppResult.Success -> logger.info { "Cover uploaded to server" }
            is AppResult.Failure -> logger.warn { "Failed to upload cover to server: ${uploadResult.message}" }
        }
        return pendingImageUploads.settle(PendingImageKind.BookCover, current.bookId, uploadResult)
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
