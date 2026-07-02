package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Instance

/**
 * Contract interface for instance-level API operations.
 *
 * Handles server instance information.
 */
internal interface InstanceApiContract {
    /**
     * Fetch the server instance information.
     *
     * This is a public endpoint - no authentication required.
     *
     * @return Result containing the Instance on success, or an error on failure
     */
    suspend fun getInstance(): AppResult<Instance>
}
