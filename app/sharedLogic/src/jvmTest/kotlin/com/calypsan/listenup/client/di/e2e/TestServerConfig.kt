package com.calypsan.listenup.client.di.e2e

import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test [ServerConfig] returning a single fixed URL — the embedded server's
 * `http://127.0.0.1:<port>` endpoint resolved at boot. Replaces the production
 * `SettingsRepositoryImpl` (SecureStorage + mDNS) in the e2e graph.
 */
internal class TestServerConfig(
    private val baseUrl: String,
) : ServerConfig {
    override val activeUrl: StateFlow<ServerUrl?> = MutableStateFlow(ServerUrl(baseUrl))

    override suspend fun setServerUrl(url: ServerUrl) = Unit

    override suspend fun getServerUrl(): ServerUrl = ServerUrl(baseUrl)

    override suspend fun hasServerConfigured(): Boolean = true

    override suspend fun setRemoteUrl(url: String?) = Unit

    override suspend fun getRemoteUrl(): ServerUrl? = null

    override suspend fun getActiveUrl(): ServerUrl = ServerUrl(baseUrl)

    override suspend fun switchToFallbackUrl(): ServerUrl? = null

    override suspend fun setActiveUrl(url: ServerUrl) = Unit

    private var connectedId: String? = null

    override suspend fun setConnectedServerId(id: String?) {
        connectedId = id
    }

    override suspend fun getConnectedServerId(): String? = connectedId

    override suspend fun updateLocalUrl(url: ServerUrl) = Unit

    override suspend fun disconnectFromServer() = Unit

    override suspend fun clearAll() = Unit
}
