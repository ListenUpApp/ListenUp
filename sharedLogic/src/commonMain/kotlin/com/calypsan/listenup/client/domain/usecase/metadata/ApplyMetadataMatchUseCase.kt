package com.calypsan.listenup.client.domain.usecase.metadata

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.suspendRunCatching
import com.calypsan.listenup.client.domain.repository.ApplyMatchRequest
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.MatchFields
import com.calypsan.listenup.client.domain.repository.MetadataBook
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.SeriesMatchEntry
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Applies an Audible metadata match to a book.
 *
 * This use case orchestrates:
 * 1. Building the match request from user selections
 * 2. Sending the request to the server
 * 3. Downloading the new cover if cover was selected
 *
 * The cover handling is best-effort - the metadata will still be applied
 * even if cover download fails. The caller can retry cover download later.
 *
 * Usage:
 * ```kotlin
 * val result = applyMetadataMatchUseCase(
 *     bookId = "book-123",
 *     asin = "B08XYZ123",
 *     region = "us",
 *     selections = selections,
 *     previewBook = preview,
 *     coverUrl = selectedCoverUrl,
 * )
 * when (result) {
 *     is Success -> navigateBack()
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class ApplyMetadataMatchUseCase(
    private val metadataRepository: MetadataRepository,
    private val imageRepository: ImageRepository,
) {
    /**
     * Apply metadata match to a book.
     *
     * @param bookId Local book ID to update
     * @param asin Audible ASIN of the matched book
     * @param region Audible region code
     * @param selections User's field selections
     * @param previewBook Full preview data for building series entries
     * @param coverUrl Optional explicit cover URL (overrides Audible if provided)
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(
        bookId: String,
        asin: String,
        region: String,
        selections: MetadataMatchSelections,
        previewBook: MetadataBook,
        coverUrl: String?,
    ): AppResult<Unit> =
        suspendRunCatching {
            logger.info { "Applying metadata match: book=$bookId, asin=$asin, region=$region" }

            // Build and send the match request
            val request =
                buildMatchRequest(
                    asin = asin,
                    region = region,
                    selections = selections,
                    previewBook = previewBook,
                    coverUrl = coverUrl,
                )
            metadataRepository.applyMatch(bookId, request)
            logger.debug { "Metadata match applied successfully" }

            // Handle cover download if cover was selected
            if (selections.cover) {
                downloadNewCover(BookId(bookId))
            }

            logger.info { "Metadata match complete for book $bookId" }
        }

    /**
     * Download the new cover from server.
     * This is best-effort - failures are logged but don't fail the operation.
     */
    private suspend fun downloadNewCover(bookId: BookId) {
        // Delete existing cover to ensure we download the new one
        when (val deleteResult = imageRepository.deleteBookCover(bookId)) {
            is Success -> logger.debug { "Deleted existing cover for $bookId" }
            is Failure -> logger.warn { "Failed to delete cover: ${deleteResult.message}" }
        }

        // Download the new cover from server
        when (val downloadResult = imageRepository.downloadBookCover(bookId)) {
            is Success -> {
                if (downloadResult.data) {
                    logger.info { "Downloaded new cover for book $bookId" }
                } else {
                    logger.info { "No cover available on server for book $bookId" }
                }
            }

            is Failure -> {
                logger.warn { "Failed to download cover for book $bookId: ${downloadResult.message}" }
                // Don't fail the operation - metadata was still applied
            }
        }
    }

    /**
     * Build the match request from user selections.
     */
    private fun buildMatchRequest(
        asin: String,
        region: String,
        selections: MetadataMatchSelections,
        previewBook: MetadataBook,
        coverUrl: String?,
    ): ApplyMatchRequest =
        ApplyMatchRequest(
            asin = asin,
            region = region,
            fields =
                MatchFields(
                    title = selections.title,
                    subtitle = selections.subtitle,
                    description = selections.description,
                    publisher = selections.publisher,
                    releaseDate = selections.releaseDate,
                    language = selections.language,
                    cover = selections.cover,
                ),
            authors = selections.selectedAuthors.toList(),
            narrators = selections.selectedNarrators.toList(),
            series =
                previewBook.series
                    .filter { it.asin in selections.selectedSeries }
                    .mapNotNull { series ->
                        series.asin?.let { seriesAsin ->
                            SeriesMatchEntry(
                                asin = seriesAsin,
                                applyName = true,
                                applySequence = true,
                            )
                        }
                    },
            genres = selections.selectedGenres.toList(),
            coverUrl = coverUrl,
        )
}

/**
 * User's selections for which metadata fields to apply.
 *
 * This is a domain model that mirrors MetadataSelections from the presentation layer,
 * but lives in the domain layer for use case independence.
 */
data class MetadataMatchSelections(
    // Simple field flags
    val cover: Boolean = true,
    val title: Boolean = true,
    val subtitle: Boolean = true,
    val description: Boolean = true,
    val publisher: Boolean = true,
    val releaseDate: Boolean = true,
    val language: Boolean = true,
    // Selected items by identifier (ASINs for contributors/series, names for genres)
    val selectedAuthors: Set<String> = emptySet(),
    val selectedNarrators: Set<String> = emptySet(),
    val selectedSeries: Set<String> = emptySet(),
    val selectedGenres: Set<String> = emptySet(),
)
