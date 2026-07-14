package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

private val log = loggerFor<ContributorMetadataApplier>()

/**
 * Applies Audible contributor metadata to an existing contributor row.
 *
 * Fetches the [AudibleContributorProfile] for [asin] in [region] by scraping
 * the Audible author page, then enriches the existing [ContributorRepository]
 * row in-place:
 *  - `asin` stamp
 *  - `description` (biography)
 *  - `imagePath` — downloads the photo to `contributors/{id}.jpg` relative to
 *    [imageHome]; **BlurHash deferred** (no Kotlin-native image decoder;
 *    `imageBlurHash` stays null). Task 19's OrphanImageCleanupTask reclaims
 *    the orphan file if the DB write rolls back.
 *
 * Returns [MetadataError.NotFound] when the contributor is absent from the DB.
 * Returns the [MetadataError] from [MetadataService] on Audible API failures.
 *
 * All writes go through the substrate's `upsert`, so revisions are bumped and
 * SSE change events are published automatically.
 */
internal class ContributorMetadataApplier(
    private val contributorRepository: ContributorRepository,
    private val imageStorage: ImageStorage,
    private val metadataService: MetadataService,
    private val imageHome: Path,
) {
    suspend fun apply(
        contributorId: ContributorId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit> {
        val existing =
            contributorRepository.findById(contributorId.value)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(
                        debugInfo = "Contributor ${contributorId.value} not found in the database.",
                    ),
                )

        return metadataService.getContributor(region, asin).flatMap { profile ->
            if (profile == null) {
                return@flatMap AppResult.Failure(
                    MetadataError.NotFound(
                        debugInfo = "No Audible contributor profile for ASIN $asin in region $region.",
                    ),
                )
            }

            val imagePath = profile.downloadImage(contributorId)

            val updated =
                existing.copy(
                    asin = asin,
                    description = profile.biography.takeIf { it.isNotBlank() },
                    imagePath = imagePath,
                    // imageBlurHash: BlurHash deferred — no Kotlin-native image decoder.
                )

            contributorRepository.upsert(updated, clientOpId = null).flatMap { AppResult.Success(Unit) }
        }
    }

    /**
     * Downloads the contributor photo from [AudibleContributorProfile.imageUrl] and
     * stores it at `contributors/{contributorId}.jpg` relative to [imageHome].
     * Returns the relative path to store in the DB, or `null` when [imageUrl]
     * is blank or the download fails (failure is logged, not propagated — photo
     * is best-effort).
     */
    private suspend fun AudibleContributorProfile.downloadImage(contributorId: ContributorId): String? {
        val url = imageUrl.takeIf { it.isNotBlank() } ?: return null
        val dir = Path(imageHome.toString(), "contributors")
        return try {
            kotlinx.io.files.SystemFileSystem
                .createDirectories(dir)
            val bytes = imageStorage.downloadBytes(url)
            // Content-addressed filename: a re-scrape with a different photo yields a new `imagePath`,
            // which is what the client keys its image cache on (the path is the version). With a stable
            // id-based name the path never changes and clients keep rendering the old photo.
            val sha = hashBytesSha256(bytes)
            val relPath = "contributors/$sha.jpg"
            imageStorage.writeBytes(bytes, Path(imageHome.toString(), relPath))
            relPath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Photo download failed for contributor ${contributorId.value} (ASIN $asin) — skipping" }
            null
        }
    }
}
