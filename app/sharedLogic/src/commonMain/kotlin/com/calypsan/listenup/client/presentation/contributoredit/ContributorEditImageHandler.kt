package com.calypsan.listenup.client.presentation.contributoredit

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.domain.repository.PendingImageUploads
import com.calypsan.listenup.client.domain.usecase.SaveOutcome
import com.calypsan.listenup.client.domain.usecase.settle
import com.calypsan.listenup.core.ContributorId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handles contributor portrait staging, offline-first commit + upload, re-framing, and staging
 * cleanup for [ContributorEditViewModel].
 */
internal class ContributorEditImageHandler(
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
    private val pendingImageUploads: PendingImageUploads,
    private val contributorEditRepository: ContributorEditRepository,
) {
    /** Stage a newly picked image locally for preview without uploading until Save. */
    suspend fun stage(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<String> =
        when (val saveResult = imageStagingRepository.saveContributorImageStaging(contributorId, imageData)) {
            is AppResult.Success -> {
                val stagingPath = imageStagingRepository.getContributorImageStagingPath(contributorId)
                logger.info { "Contributor image saved to staging for preview: $stagingPath" }
                AppResult.Success(stagingPath)
            }

            is AppResult.Failure -> {
                logger.error { "Failed to save contributor image to staging: ${saveResult.message}" }
                saveResult
            }
        }

    /**
     * Commit the staged image to the durable local location and upload it to the server.
     * The local save is prioritized so the picked photo survives offline; a failed upload does not
     * fail the save but is queued to retry when it can succeed later, and reported as
     * [SaveOutcome.SavedImageNotUploaded].
     */
    suspend fun commitAndUploadIfPending(current: ContributorEditForm): SaveOutcome {
        val pendingData = current.pendingImageData ?: return SaveOutcome.Saved
        val pendingFilename = current.pendingImageFilename ?: return SaveOutcome.Saved
        val contributorId = current.contributorId

        // Room first: the new photo's frame (or its absence) is recorded for these exact bytes before any upload, so it
        // shows at once and a retried upload carries it (PendingImageUploads attaches it by hash).
        when (
            val stamped =
                contributorEditRepository.recordNewPhotoFrame(
                    ContributorId(contributorId),
                    pendingData,
                    current.pendingImageFrame,
                )
        ) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> logger.error { "Failed to record the new photo's frame: ${stamped.message}" }
        }

        // Then commit staging to the main image location (offline-first).
        when (val commitResult = imageStagingRepository.commitContributorImageStaging(contributorId)) {
            is AppResult.Success -> logger.info { "Staging image committed to main location" }
            is AppResult.Failure -> logger.error { "Failed to commit staging image: ${commitResult.message}" }
        }

        // Then upload to the server; the local image is already saved either way.
        val result =
            imageRepository.uploadContributorImage(
                contributorId = contributorId,
                imageData = pendingData,
                filename = pendingFilename,
                frame = current.pendingImageFrame,
            )
        when (result) {
            is AppResult.Success -> logger.info { "Contributor image uploaded to server" }
            is AppResult.Failure -> logger.error { "Failed to upload contributor image: ${result.message}" }
        }
        return pendingImageUploads.settle(PendingImageKind.ContributorImage, contributorId, result)
    }

    /** Persist a crop-frame change when no new photo was staged, or return null if nothing is pending. */
    suspend fun applyReframeIfPending(current: ContributorEditForm): AppResult<Unit>? {
        val reframe = current.pendingImageFrame ?: return null
        if (current.pendingImageData != null) return null
        return contributorEditRepository.setImageFrame(ContributorId(current.contributorId), reframe)
    }

    fun cleanUp(contributorId: String) {
        imageStagingRepository.requestContributorImageStagingCleanup(contributorId)
    }
}
