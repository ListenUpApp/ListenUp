package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.VerifiedServer

/**
 * Stub [InstanceRepository] for the auth end-to-end fixture — `AuthSessionStore` reads it
 * during `checkServerStatus()` and `initializeAuthState()`, but those paths
 * are never exercised by the auth round-trip we test here. A failing stub is
 * enough; if a future test needs the instance check, replace this with a real
 * impl pointing at the embedded `/api/v1/instance`.
 */
internal class StubInstanceRepository : InstanceRepository {
    override suspend fun findReachableUrl(urls: List<String>): String? = urls.firstOrNull()

    override suspend fun getServerInfo(forceRefresh: Boolean): AppResult<ServerInfo> =
        AppResult.Failure(InternalError(debugInfo = "server info not used in F12"))

    override suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer> =
        AppResult.Failure(InternalError(debugInfo = "instance verify not used in F12"))
}
