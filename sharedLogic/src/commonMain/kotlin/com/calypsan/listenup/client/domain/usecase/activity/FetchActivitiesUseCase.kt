package com.calypsan.listenup.client.domain.usecase.activity

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Fetches activities from the server and caches them locally.
 *
 * Used for initial population of the activity feed when Room is empty,
 * and for manual refresh operations. After initial fetch, the activity
 * feed works offline via Room observation.
 *
 * Usage:
 * ```kotlin
 * val result = fetchActivitiesUseCase(limit = 50)
 * when (result) {
 *     is AppResult.Success -> logger.info { "Fetched ${result.data} activities" }
 *     is AppResult.Failure -> logger.error { "Failed: ${result.message}" }
 * }
 * ```
 */
open class FetchActivitiesUseCase(
    private val activityRepository: ActivityRepository,
) {
    /**
     * Fetch activities from API and cache locally.
     *
     * @param limit Maximum number of activities to fetch
     * @return Result containing the number of activities fetched, or a failure
     */
    open suspend operator fun invoke(limit: Int): AppResult<Int> {
        logger.debug { "Fetching activities (limit=$limit)" }
        return activityRepository.fetchAndCacheActivities(limit)
    }
}
