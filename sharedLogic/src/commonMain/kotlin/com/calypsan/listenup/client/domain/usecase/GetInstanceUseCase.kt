package com.calypsan.listenup.client.domain.usecase

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.repository.InstanceRepository

/**
 * Use case for retrieving server instance information.
 *
 * This use case encapsulates the business logic for fetching instance data.
 * It provides a clean interface for the presentation layer without exposing
 * repository details.
 *
 * Follows the operator invoke pattern for clean call-site syntax:
 * ```kotlin
 * val result = getInstanceUseCase()
 * ```
 */
class GetInstanceUseCase(
    private val repository: InstanceRepository,
) {
    /**
     * Execute the use case to get instance information.
     *
     * @param forceRefresh If true, forces a fresh fetch from the server
     * @return Result containing the Instance or an error
     */
    suspend operator fun invoke(forceRefresh: Boolean = false): AppResult<Instance> =
        repository.getInstance(forceRefresh)
}
