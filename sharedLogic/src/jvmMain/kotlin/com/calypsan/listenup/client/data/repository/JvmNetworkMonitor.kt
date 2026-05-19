package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
private const val HEALTH_CHECK_TIMEOUT_MS = 5_000L

/**
 * JVM desktop implementation of [NetworkMonitor] using health check polling.
 *
 * Instead of relying on OS-level network state (which doesn't guarantee
 * server reachability), this implementation periodically checks if the
 * server's health endpoint is reachable.
 *
 * Features:
 * - Polls server health endpoint every 30 seconds
 * - Immediate recheck capability via [recheckNow]
 * - Assumes online if no server URL is configured (optimistic default)
 * - Desktop networks are always considered unmetered
 *
 * @param serverUrlProvider Function that returns the current server URL, or null if not configured
 */
class JvmNetworkMonitor(
    private val serverUrlProvider: () -> String?,
) : NetworkMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient =
        HttpClient(OkHttp) {
            installListenUpErrorHandling()

            engine {
                config {
                    connectTimeout(HEALTH_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    readTimeout(HEALTH_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }

    private val _isOnlineFlow = MutableStateFlow(true) // Optimistic default
    override val isOnlineFlow: StateFlow<Boolean> = _isOnlineFlow.asStateFlow()

    // Desktop networks are always considered unmetered (WiFi/Ethernet)
    private val _isOnUnmeteredNetworkFlow = MutableStateFlow(true)
    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = _isOnUnmeteredNetworkFlow.asStateFlow()

    init {
        startHealthCheckLoop()
    }

    override fun isOnline(): Boolean = _isOnlineFlow.value

    /**
     * Trigger an immediate health check.
     * Useful when a network operation fails and we want to update state.
     */
    fun recheckNow() {
        scope.launch {
            checkHealth()
        }
    }

    private fun startHealthCheckLoop() {
        scope.launch {
            while (true) {
                checkHealth()
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkHealth() {
        val serverUrl = serverUrlProvider()

        if (serverUrl == null) {
            // No server configured - assume online (optimistic)
            _isOnlineFlow.value = true
            return
        }

        val isReachable =
            try {
                val response = httpClient.get("$serverUrl/health")
                response.status.isSuccess()
            } catch (e: Exception) {
                logger.debug(e) { "Health check failed for $serverUrl" }
                false
            }

        if (_isOnlineFlow.value != isReachable) {
            logger.info { "Network state changed: online=$isReachable" }
            _isOnlineFlow.value = isReachable
        }
    }
}
