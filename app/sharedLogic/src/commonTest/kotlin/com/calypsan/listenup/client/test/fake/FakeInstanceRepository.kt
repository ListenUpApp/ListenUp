package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.VerifiedServer

/** An [InstanceRepository] answering a fixed [ServerInfo]; [permissionFlags] is what the server advertises. */
class FakeInstanceRepository(
    private val permissionFlags: Set<String> = setOf("canEdit", "canCurateLibrary"),
) : InstanceRepository {
    override suspend fun findReachableUrl(urls: List<String>): String? = urls.firstOrNull()

    override suspend fun getServerInfo(forceRefresh: Boolean): AppResult<ServerInfo> =
        AppResult.Success(
            ServerInfo(
                name = "ListenUp",
                version = "0.0.0",
                apiVersion = "v1",
                setupRequired = false,
                registrationPolicy = RegistrationPolicy.OPEN,
                instanceId = "test-instance",
                permissionFlags = permissionFlags,
            ),
        )

    override suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer> =
        getServerInfo().map { VerifiedServer(serverInfo = it, verifiedUrl = baseUrl) }
}
