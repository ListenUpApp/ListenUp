package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.error.UnknownError
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.VerifiedServer

/**
 * Stub [InstanceRepository] for the F12 fixture — `AuthSessionStore` reads it
 * during `checkServerStatus()` and `initializeAuthState()`, but those paths
 * are never exercised by the auth round-trip we test here. A failing stub is
 * enough; if a future test needs the instance check, replace this with a real
 * impl pointing at the embedded `/api/v1/instance`.
 */
internal class StubInstanceRepository : InstanceRepository {
    override suspend fun findReachableUrl(urls: List<String>): String? = urls.firstOrNull()

    override suspend fun getInstance(forceRefresh: Boolean): AppResult<Instance> =
        AppResult.Failure(UnknownError(debugInfo = "instance lookup not used in F12"))

    override suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer> =
        AppResult.Failure(UnknownError(debugInfo = "instance verify not used in F12"))
}
