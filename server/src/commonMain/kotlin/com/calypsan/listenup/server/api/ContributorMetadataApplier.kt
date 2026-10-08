package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.matching.person.ContributorPhotoFiles
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.hardcover.HARDCOVER_AUTHOR_KEY_PREFIX
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
 *  - `imagePath` — downloads the photo through [ImageStore] into `contributors/`, which
 *    validates and names it `{sha}.{ext}` ([ContributorPhotoFiles]) from the sniffed kind; a failed, oversized, or
 *    not-actually-an-image download keeps the existing photo. The OrphanImageCleanupTask
 *    reclaims the orphan file if the DB write rolls back.
 *
 * Returns [MetadataError.NotFound] when the contributor is absent from the DB,
 * when no catalog has a profile for the ASIN, or when the profile is an empty
 * regional shell (no biography AND no photo) — an honest miss, matching what
 * Audiobookshelf does with the same Audnexus upstream.
 *
 * All writes go through the substrate's `upsert`, so revisions are bumped and
 * Sync change events are published automatically.
 */
internal class ContributorMetadataApplier(
    private val contributorRepository: ContributorRepository,
    imageStorage: ImageStorage,
    private val coordinator: EnrichmentCoordinator,
    imageHome: Path,
    photoMaxBytes: Long = ContributorPhotoFiles.CONTRIBUTOR_PHOTO_MAX_BYTES,
) {
    /** Validated, content-addressed photo storage — shared with Match details' person Apply. */
    private val photoFiles = ContributorPhotoFiles(imageStorage, imageHome, photoMaxBytes)

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
        val biography = profile.description?.takeIf { it.isNotBlank() }

        // Never overwrite an existing field with a blank incoming value (ABS truthy-guard
        // semantics): a missing bio or a failed photo download keeps what the user already has.
        // What it did write is recorded as matched, from the provider the key names.
        val matched =
            FieldProvenance(FieldSourceKind.ENRICHMENT, provider = providerOf(asin), at = currentEpochMilliseconds())
        val updated =
            existing.copy(
                asin = asin,
                description = biography ?: existing.description,
                imagePath = imagePath ?: existing.imagePath,
                fieldProvenance =
                    existing.fieldProvenance +
                        listOfNotNull(
                            ContributorField.BIOGRAPHY.takeIf { biography != null },
                            ContributorField.PHOTO.takeIf { imagePath != null },
                        ).associateWith { matched },
            )

        return contributorRepository.upsert(updated, clientOpId = null).flatMap { AppResult.Success(Unit) }
    }

    /** Stores [ContributorMeta.imageUrl]'s photo; null when there is none or it fails (the photo is best-effort). */
    private suspend fun ContributorMeta.downloadImage(contributorId: ContributorId): String? {
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: return null
        return photoFiles.store(url).also {
            if (it == null) log.warn { "Photo skipped for contributor ${contributorId.value} (ASIN $key)" }
        }
    }

    /** The provider a legacy key belongs to: Hardcover's own prefix, otherwise Audnexus (an Audible ASIN). */
    private fun providerOf(key: String): String =
        if (key.startsWith(HARDCOVER_AUTHOR_KEY_PREFIX)) {
            MetadataProviderId.HARDCOVER.value
        } else {
            MetadataProviderId.AUDNEXUS.value
        }
}
