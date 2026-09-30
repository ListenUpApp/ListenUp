package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.ServerWithStatus
import com.calypsan.listenup.client.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ServerRepository]. [servers] and [localNetworkDenied] are the live discovery state a
 * test drives; [startCount] / [stopCount] record how often discovery was started and stopped.
 */
class FakeServerRepository(
    private val serverFlow: Flow<List<ServerWithStatus>>? = null,
) : ServerRepository {
    /** The discovered servers, when no custom [serverFlow] was given. */
    val servers = MutableStateFlow<List<ServerWithStatus>>(emptyList())

    /** Whether the platform is refusing to browse for want of local network access. */
    val localNetworkDenied = MutableStateFlow(false)

    var startCount = 0
        private set

    var stopCount = 0
        private set

    override fun observeServers(): Flow<List<ServerWithStatus>> = serverFlow ?: servers

    override fun observeLocalNetworkDenied(): Flow<Boolean> = localNetworkDenied

    override fun startDiscovery() {
        startCount++
    }

    override fun stopDiscovery() {
        stopCount++
    }
}
