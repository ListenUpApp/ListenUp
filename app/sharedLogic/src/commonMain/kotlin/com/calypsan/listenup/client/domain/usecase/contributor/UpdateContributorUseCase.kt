package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Updates a contributor's metadata via the RPC contract.
 *
 * Builds a [ContributorUpdate] PATCH from the request, dropping blank optional
 * fields so the server treats them as "unchanged" rather than "clear" (the
 * editor lets users blank-out a field intentionally by leaving it empty, which
 * we honour here only by not sending a value at all).
 *
 * Merge / unmerge are deliberately absent in Books-C1 — server-canonical
 * versions land in Books-C2 alongside the `contributor_aliases` substrate.
 */
open class UpdateContributorUseCase(
    private val contributorEditRepository: ContributorEditRepository,
) {
    /**
     * Apply the [request] to the contributor identified by `request.contributorId`.
     */
    open suspend operator fun invoke(request: ContributorUpdateRequest): AppResult<Unit> {
        logger.info { "Updating contributor ${request.contributorId}" }
        val patch =
            ContributorUpdate(
                name = request.name,
                description = request.biography?.ifBlank { null },
                website = request.website?.ifBlank { null },
                birthDate = request.birthDate?.ifBlank { null },
                deathDate = request.deathDate?.ifBlank { null },
            )
        return contributorEditRepository.updateContributor(ContributorId(request.contributorId), patch)
    }
}

/**
 * Request data for updating a contributor.
 */
data class ContributorUpdateRequest(
    val contributorId: String,
    val name: String,
    val biography: String? = null,
    val website: String? = null,
    val birthDate: String? = null,
    val deathDate: String? = null,
)
