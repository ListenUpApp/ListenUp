package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Applies Audible metadata to a contributor via the RPC contract.
 *
 * The server fetches the Audible profile for [ApplyContributorMetadataRequest.asin],
 * updates the contributor entity, and emits an SSE event so that Room (the local
 * single source of truth) receives the change. No local orchestration is needed on
 * the client — image download, contributor-DB upsert, and field-selection filtering
 * are all server-side concerns (B2b "nuke legacy DTOs" decision).
 *
 * The [ApplyContributorMetadataRequest.selections] field is retained for display in
 * the preview UI but is not forwarded to the server — the server applies all
 * available fields based on the matched profile.
 */
open class ApplyContributorMetadataUseCase(
    private val metadataRepository: MetadataRepository,
) {
    /**
     * Apply Audible contributor metadata for the given [request].
     *
     * Returns [AppResult.Success] when the server accepted the apply; the
     * updated contributor arrives via SSE → Room thereafter.
     * Returns [AppResult.Failure] with a typed [com.calypsan.listenup.api.error.AppError]
     * on any network or server-side error.
     */
    open suspend operator fun invoke(request: ApplyContributorMetadataRequest): AppResult<Unit> {
        logger.info { "Applying Audible metadata to contributor ${request.contributorId}" }
        return metadataRepository.applyContributorMetadata(
            contributorId = ContributorId(request.contributorId),
            asin = request.asin,
            region = request.region,
        )
    }
}

/**
 * Request data for applying contributor metadata.
 */
data class ApplyContributorMetadataRequest(
    val contributorId: String,
    val asin: String,
    val region: MetadataLocale,
    val selections: MetadataFieldSelections,
)

/**
 * Field selections for contributor metadata application.
 *
 * Used to drive the preview UI — the server applies all available fields
 * from the matched Audible profile regardless of this selection.
 */
data class MetadataFieldSelections(
    val name: Boolean = true,
    val biography: Boolean = true,
    val image: Boolean = true,
) {
    val hasAnySelected: Boolean
        get() = name || biography || image
}
