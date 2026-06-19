package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-only [ServerConfig] that returns a single fixed URL — the embedded
 * server's `http://127.0.0.1:<port>` endpoint resolved at boot.
 *
 * Production [ServerConfig] is `SettingsRepositoryImpl` reading from
 * `SecureStorage` plus mDNS rediscovery. None of that machinery is relevant
 * to F12, which is exercising the contract round-trip across a real wire.
 */
internal class TestServerConfig(
    private val baseUrl: String,
) : ServerConfig {
    override suspend fun setServerUrl(url: ServerUrl) = Unit

    override suspend fun getServerUrl(): ServerUrl = ServerUrl(baseUrl)

    override suspend fun hasServerConfigured(): Boolean = true

    override suspend fun setRemoteUrl(url: String?) = Unit

    override suspend fun getRemoteUrl(): ServerUrl? = null

    override suspend fun getActiveUrl(): ServerUrl = ServerUrl(baseUrl)

    override val activeUrl: StateFlow<ServerUrl?> = MutableStateFlow(ServerUrl(baseUrl)).asStateFlow()

    override suspend fun setActiveUrl(url: ServerUrl) = Unit

    override suspend fun switchToFallbackUrl(): ServerUrl? = null

    override suspend fun setConnectedServerId(id: String?) = Unit

    override suspend fun getConnectedServerId(): String? = null

    override suspend fun updateLocalUrl(url: ServerUrl) = Unit

    override suspend fun disconnectFromServer() = Unit

    override suspend fun clearAll() = Unit
}
