package com.calypsan.listenup.client.domain.usecase.series

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Updates a series with optional cover upload.
 *
 * This use case orchestrates:
 * 1. Updating series metadata (name, description)
 * 2. Committing staged cover to main location
 * 3. Uploading cover to server (best-effort)
 *
 * Cover Flow:
 * - Cover staging happens separately in the ViewModel for preview
 * - This use case commits the staged cover to the main location
 * - Then uploads to server (failures are logged but don't fail the operation)
 *
 * Usage:
 * ```kotlin
 * val result = updateSeriesUseCase(
 *     request = SeriesUpdateRequest(
 *         seriesId = "series-123",
 *         name = "Updated Name",
 *         description = "Updated description",
 *         pendingCoverData = byteArrayOf(...),
 *         pendingCoverFilename = "cover.jpg",
 *     )
 * )
 * ```
 */
open class UpdateSeriesUseCase(
    private val seriesEditRepository: SeriesEditRepository,
    private val imageRepository: ImageRepository,
    private val imageStagingRepository: ImageStagingRepository,
) {
    /**
     * Update series with optional cover upload.
     *
     * @param request The update request containing all changes
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(request: SeriesUpdateRequest): AppResult<Unit> =
        suspendRunCatching {
            logger.info { "Updating series ${request.seriesId}" }

            // 1. Update metadata if changed
            if (request.metadataChanged) {
                val name = if (request.nameChanged) request.name else null
                val description =
                    if (request.descriptionChanged) {
                        request.description?.ifBlank { null }
                    } else {
                        null
                    }

                val patch = SeriesUpdate(name = name, description = description)
                when (val result = seriesEditRepository.updateSeries(SeriesId(request.seriesId), patch)) {
                    is AppResult.Success -> {
                        logger.info { "Series metadata updated" }
                    }

                    is AppResult.Failure -> {
                        throw null as Exception? ?: Exception(result.message)
                    }
                }
            }

            // 2. Commit staging cover and upload if pending
            if (request.pendingCoverData != null && request.pendingCoverFilename != null) {
                commitAndUploadCover(request)
            }

            logger.info { "Series update complete for ${request.seriesId}" }
        }

    /**
     * Commit staged cover to main location and upload to server.
     * Cover upload is best-effort - local save is prioritized.
     */
    private suspend fun commitAndUploadCover(request: SeriesUpdateRequest) {
        val pendingData = request.pendingCoverData ?: return
        val pendingFilename = request.pendingCoverFilename ?: return

        // First, commit staging to main cover location
        when (val commitResult = imageStagingRepository.commitSeriesCoverStaging(request.seriesId)) {
            is AppResult.Success -> {
                logger.info { "Staging cover committed to main location" }
            }

            is AppResult.Failure -> {
                logger.error { "Failed to commit staging cover: ${commitResult.message}" }
                // Continue anyway - try to upload
            }
        }

        // Then upload to server (best-effort)
        when (val result = imageRepository.uploadSeriesCover(request.seriesId, pendingData, pendingFilename)) {
            is AppResult.Success -> {
                logger.info { "Cover uploaded to server" }
            }

            is AppResult.Failure -> {
                logger.error { "Failed to upload cover: ${result.message}" }
                logger.warn { "Continuing despite cover upload failure (local cover saved)" }
                // Don't fail - local cover is saved
            }
        }
    }
}

/**
 * Request data for updating a series.
 */
data class SeriesUpdateRequest(
    val seriesId: String,
    val name: String,
    val description: String? = null,
    /**
     * Whether metadata fields have changed.
     */
    val metadataChanged: Boolean = false,
    val nameChanged: Boolean = false,
    val descriptionChanged: Boolean = false,
    /**
     * Pending cover data to upload.
     * If present, staging cover will be committed and uploaded.
     */
    val pendingCoverData: ByteArray? = null,
    val pendingCoverFilename: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SeriesUpdateRequest

        if (seriesId != other.seriesId) return false
        if (name != other.name) return false
        if (description != other.description) return false
        if (metadataChanged != other.metadataChanged) return false
        if (nameChanged != other.nameChanged) return false
        if (descriptionChanged != other.descriptionChanged) return false
        if (pendingCoverData != null) {
            if (other.pendingCoverData == null) return false
            if (!pendingCoverData.contentEquals(other.pendingCoverData)) return false
        } else if (other.pendingCoverData != null) {
            return false
        }
        if (pendingCoverFilename != other.pendingCoverFilename) return false

        return true
    }

    override fun hashCode(): Int {
        var result = seriesId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + metadataChanged.hashCode()
        result = 31 * result + nameChanged.hashCode()
        result = 31 * result + descriptionChanged.hashCode()
        result = 31 * result + (pendingCoverData?.contentHashCode() ?: 0)
        result = 31 * result + (pendingCoverFilename?.hashCode() ?: 0)
        return result
    }
}
