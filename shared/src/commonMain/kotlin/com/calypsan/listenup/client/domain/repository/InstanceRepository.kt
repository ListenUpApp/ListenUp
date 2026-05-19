package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.Instance

/**
 * Result of server verification containing the instance and verified URL.
 *
 * @property instance The server instance information
 * @property verifiedUrl The URL that successfully connected (may include protocol that worked)
 */
data class VerifiedServer(
    val instance: Instance,
    val verifiedUrl: String,
)

/**
 * Repository for managing server instance data.
 *
 * This interface defines the contract for accessing instance information.
 * Implementations handle data fetching, caching, and error handling.
 */
interface InstanceRepository {
    // Try multiple URLs to find one that's reachable.
    // Returns the first URL that responds, or null if none work.
    suspend fun findReachableUrl(urls: List<String>): String?

    /**
     * Retrieves the current server instance information.
     *
     * @param forceRefresh If true, bypasses any cached data and fetches fresh data from the server
     * @return Result containing the Instance on success, or an error on failure
     */
    suspend fun getInstance(forceRefresh: Boolean = false): AppResult<Instance>

    /**
     * Verifies a server URL is a valid ListenUp instance before authentication.
     *
     * Used during initial server connection setup. Creates an unauthenticated
     * HTTP client to fetch /api/v1/instance and verify the server is ListenUp.
     * Tries HTTPS first, then falls back to HTTP if SSL fails.
     *
     * @param baseUrl The server URL to verify (with or without protocol)
     * @return Result containing VerifiedServer with instance and working URL on success
     */
    suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer>
}
