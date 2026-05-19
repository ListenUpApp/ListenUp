package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.failureOf
import com.calypsan.listenup.core.validationError
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorMetadataResult
import com.calypsan.listenup.client.domain.model.ContributorWithMetadata
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Applies Audible metadata to a contributor.
 *
 * This use case orchestrates the full metadata application flow:
 * 1. Calls server API to apply selected metadata fields
 * 2. Downloads the contributor image if available
 * 3. Saves the image locally for offline access
 * 4. Updates the local database with the enriched contributor
 *
 * The server handles fetching data from Audible and updating the contributor.
 * This use case then syncs the result to the local database with the image.
 *
 * Usage:
 * ```kotlin
 * val result = applyContributorMetadataUseCase(
 *     ApplyContributorMetadataRequest(
 *         contributorId = "contributor-123",
 *         asin = "B001ABC123",
 *         imageUrl = "https://audible.com/image.jpg",
 *         selections = MetadataFieldSelections(name = true, biography = true, image = true),
 *     )
 * )
 * when (result) {
 *     is Success -> navigateBack()
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class ApplyContributorMetadataUseCase(
    private val metadataRepository: MetadataRepository,
    private val imageRepository: ImageRepository,
    private val contributorRepository: ContributorRepository,
) {
    /**
     * Apply Audible metadata to a contributor.
     *
     * @param request The metadata application request
     * @return Success with the updated contributor, or Failure with error message
     */
    open suspend operator fun invoke(request: ApplyContributorMetadataRequest): AppResult<Contributor> {
        if (!request.selections.hasAnySelected) {
            return validationError("Please select at least one field to apply")
        }

        logger.info { "Applying Audible metadata to contributor ${request.contributorId}" }

        return when (
            val apiResult =
                metadataRepository.applyContributorMetadata(
                    contributorId = request.contributorId,
                    asin = request.asin,
                    imageUrl = request.imageUrl,
                    applyName = request.selections.name,
                    applyBiography = request.selections.biography,
                    applyImage = request.selections.image,
                )
        ) {
            is ContributorMetadataResult.Success -> {
                handleSuccess(request.contributorId, apiResult)
            }

            is ContributorMetadataResult.NeedsDisambiguation -> {
                // Shouldn't happen when ASIN is provided
                logger.warn { "Unexpected disambiguation request when ASIN was provided" }
                failureOf("Unexpected disambiguation request")
            }

            is ContributorMetadataResult.Error -> {
                logger.warn { "Server rejected metadata application: ${apiResult.message}" }
                failureOf(apiResult.message)
            }
        }
    }

    /**
     * Handle successful API response by downloading image and updating local database.
     */
    private suspend fun handleSuccess(
        contributorId: String,
        apiResult: ContributorMetadataResult.Success,
    ): AppResult<Contributor> {
        val contributorData =
            apiResult.contributor
                ?: return failureOf("Metadata was applied but contributor data was not returned")

        val existing = contributorRepository.getById(contributorId)
        var contributor = contributorData.toDomain(existing)

        // Download and save image locally if server returned an image URL
        if (contributorData.imageUrl != null) {
            contributor = downloadAndSaveImage(contributorId, contributor)
        }

        // Update local database
        contributorRepository.upsertContributor(contributor)
        logger.info { "Applied Audible metadata to contributor $contributorId" }

        return Success(contributor)
    }

    /**
     * Download contributor image from server and save locally.
     * Returns the contributor with updated imagePath, or original contributor if download fails.
     */
    private suspend fun downloadAndSaveImage(
        contributorId: String,
        contributor: Contributor,
    ): Contributor {
        val downloadResult = imageRepository.downloadContributorImage(contributorId)

        if (downloadResult is Failure) {
            logger.warn { "Failed to download contributor image: ${downloadResult.message}" }
            return contributor
        }

        val imageData = (downloadResult as Success).data
        val saveResult = imageRepository.saveContributorImage(contributorId, imageData)

        if (saveResult is Failure) {
            logger.warn { "Failed to save contributor image locally: ${saveResult.message}" }
            return contributor
        }

        val localPath = imageRepository.getContributorImagePath(contributorId)
        logger.debug { "Downloaded and saved contributor image locally: $localPath" }

        return contributor.copy(imagePath = localPath)
    }
}

/**
 * Extension to convert ContributorWithMetadata to Contributor.
 */
private fun ContributorWithMetadata.toDomain(existing: Contributor? = null): Contributor =
    Contributor(
        id =
            com.calypsan.listenup.core
                .ContributorId(id),
        name = name,
        description = biography,
        imagePath = existing?.imagePath, // Preserve local image path; updated after download if needed
        imageBlurHash = imageBlurHash,
        website = existing?.website,
        birthDate = existing?.birthDate,
        deathDate = existing?.deathDate,
        aliases = existing?.aliases ?: emptyList(),
    )

/**
 * Request data for applying contributor metadata.
 */
data class ApplyContributorMetadataRequest(
    val contributorId: String,
    val asin: String,
    val imageUrl: String?,
    val selections: MetadataFieldSelections,
)

/**
 * Field selections for contributor metadata application.
 */
data class MetadataFieldSelections(
    val name: Boolean = true,
    val biography: Boolean = true,
    val image: Boolean = true,
) {
    val hasAnySelected: Boolean
        get() = name || biography || image
}
