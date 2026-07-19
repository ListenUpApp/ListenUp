package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.services.ContributorRepository
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

private val log = loggerFor<ContributorMetadataApplier>()

/**
 * Applies external contributor metadata to an existing contributor row.
 *
 * Composes the [ContributorMeta] profile for [asin] in a locale through the
 * [EnrichmentCoordinator] (served by Audnexus's `ContributorSource`), then enriches
 * the existing [ContributorRepository] row in-place:
 *  - `asin` stamp
 *  - `description` (biography) — only when the profile carries a non-blank one;
 *    a blank incoming value keeps the existing biography
 *  - `imagePath` — downloads the photo to `contributors/{sha}.jpg` relative to
 *    [imageHome]; a failed download keeps the existing photo. The
 *    OrphanImageCleanupTask reclaims the orphan file if the DB write rolls back.
 *
 * Returns [MetadataError.NotFound] when the contributor is absent from the DB,
 * when no catalog has a profile for the ASIN, or when the profile is an empty
 * regional shell (no biography AND no photo) — an honest miss, matching what
 * Audiobookshelf does with the same Audnexus upstream.
 *
 * All writes go through the substrate's `upsert`, so revisions are bumped and
 * SSE change events are published automatically.
 */
internal class ContributorMetadataApplier(
    private val contributorRepository: ContributorRepository,
    private val imageStorage: ImageStorage,
    private val coordinator: EnrichmentCoordinator,
    private val imageHome: Path,
) {
    suspend fun apply(
        contributorId: ContributorId,
        asin: String,
        locale: MetadataLocale,
    ): AppResult<Unit> {
        val existing =
            contributorRepository.findById(contributorId.value)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(
                        debugInfo = "Contributor ${contributorId.value} not found in the database.",
                    ),
                )

        val profile =
            coordinator.getContributor(asin, locale)
                ?: return AppResult.Failure(
                    MetadataError.NotFound(
                        debugInfo = "No contributor profile for ASIN $asin in region ${locale.region}.",
                    ),
                )

        // ABS-verified honest-miss guard: a profile with neither biography nor photo is an
        // empty regional shell (Audnexus returns HTTP 200 with no content for cross-region
        // fetches) — applying it could only stamp an ASIN while wiping nothing, so refuse.
        if (profile.description.isNullOrBlank() && profile.imageUrl.isNullOrBlank()) {
            return AppResult.Failure(
                MetadataError.NotFound(
                    debugInfo = "Profile for ASIN $asin has no data in region ${locale.region}.",
                ),
            )
        }

        val imagePath = profile.downloadImage(contributorId)

        // Never overwrite an existing field with a blank incoming value (ABS truthy-guard
        // semantics): a missing bio or a failed photo download keeps what the user already has.
        val updated =
            existing.copy(
                asin = asin,
                description = profile.description?.takeIf { it.isNotBlank() } ?: existing.description,
                imagePath = imagePath ?: existing.imagePath,
            )

        return contributorRepository.upsert(updated, clientOpId = null).flatMap { AppResult.Success(Unit) }
    }

    /**
     * Downloads the contributor photo from [ContributorMeta.imageUrl] and stores it at
     * `contributors/{sha}.jpg` relative to [imageHome]. Returns the relative path to
     * store in the DB, or `null` when the URL is absent or the download fails (failure
     * is logged, not propagated — the photo is best-effort).
     */
    private suspend fun ContributorMeta.downloadImage(contributorId: ContributorId): String? {
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: return null
        val dir = Path(imageHome.toString(), "contributors")
        return try {
            kotlinx.io.files.SystemFileSystem
                .createDirectories(dir)
            val bytes = imageStorage.downloadBytes(url)
            // Content-addressed filename: a re-fetch with a different photo yields a new `imagePath`,
            // which is what the client keys its image cache on (the path is the version). With a stable
            // id-based name the path never changes and clients keep rendering the old photo.
            val sha = hashBytesSha256(bytes)
            val relPath = "contributors/$sha.jpg"
            imageStorage.writeBytes(bytes, Path(imageHome.toString(), relPath))
            relPath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Photo download failed for contributor ${contributorId.value} (ASIN $key) — skipping" }
            null
        }
    }
}
