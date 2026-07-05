@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.valueOrNull
import com.calypsan.listenup.client.domain.model.Instance
import io.github.oshai.kotlinlogging.KotlinLogging

private val instanceRepositoryLogger = KotlinLogging.logger {}

/**
 * Result of server verification: the server's [ServerInfo] and the URL that
 * successfully connected.
 *
 * @property serverInfo The verified server's identity, setup state, and registration policy.
 * @property verifiedUrl The URL that connected (the protocol variant that worked).
 */
data class VerifiedServer(
    val serverInfo: ServerInfo,
    val verifiedUrl: String,
)

/**
 * Repository for server-instance data.
 *
 * Verification and the pre-auth [getServerInfo] probe go over kotlinx.rpc
 * ([ServerInfo]); the legacy [getInstance] REST path remains for admin/settings
 * consumers that still read the richer [Instance] (e.g. `remoteUrl`) until those
 * screens migrate.
 */
interface InstanceRepository {
    // Try multiple URLs to find one that's reachable.
    // Returns the first URL that responds, or null if none work.
    suspend fun findReachableUrl(urls: List<String>): String?

    /**
     * Fetches the current server's [ServerInfo] over RPC (the screen-one probe).
     *
     * @param forceRefresh If true, bypasses any cached value and re-fetches.
     * @return [ServerInfo] on success, or a typed failure (e.g. no URL configured / unreachable).
     */
    suspend fun getServerInfo(forceRefresh: Boolean = false): AppResult<ServerInfo>

    /**
     * iOS-safe accessor: the [ServerInfo] or `null` on failure (folded in Kotlin). Use from Swift —
     * never `await` the `AppResult`-returning [getServerInfo] (Swift Export bridge trap). This is
     * the RPC-backed accessor for the share-link / server-identity path.
     */
    suspend fun getServerInfoOrNull(forceRefresh: Boolean = false): ServerInfo? =
        getServerInfo(forceRefresh).valueOrNull {
            instanceRepositoryLogger.warn { "getServerInfoOrNull: ${it.debugInfo ?: it.message}" }
        }

    /**
     * Legacy REST fetch of the richer [Instance] aggregate.
     *
     * Retained only for admin/settings consumers that read fields absent from
     * [ServerInfo] (notably `remoteUrl`). Migrates away once a dedicated admin
     * GET surface exists. New code should prefer [getServerInfo].
     */
    suspend fun getInstance(forceRefresh: Boolean = false): AppResult<Instance>

    /**
     * Verifies a server URL is a valid ListenUp instance before authentication.
     *
     * Connects an unauthenticated kotlinx.rpc proxy to the candidate server's
     * public mount and fetches [ServerInfo]. Tries HTTPS/WSS first, falls back to
     * HTTP/WS, mirroring the protocol the user's URL implies.
     *
     * @param baseUrl The server URL to verify (with or without protocol).
     * @return [VerifiedServer] with the [ServerInfo] and the working URL on success.
     */
    suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer>
}
