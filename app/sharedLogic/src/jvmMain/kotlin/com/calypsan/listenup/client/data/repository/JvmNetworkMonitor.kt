package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.appCoroutineExceptionHandler
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
private const val HEALTH_CHECK_TIMEOUT_MS = 5_000L

private fun createHealthCheckClient(): HttpClient =
    HttpClient(OkHttp) {
        installListenUpErrorHandling()

        engine {
            config {
                connectTimeout(HEALTH_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                readTimeout(HEALTH_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

/**
 * JVM desktop implementation of [NetworkMonitor] using health check polling.
 *
 * Instead of relying on OS-level network state (which doesn't guarantee
 * server reachability), this implementation periodically checks if the
 * server's health endpoint is reachable.
 *
 * Features:
 * - Polls server health endpoint every 30 seconds
 * - Assumes online if no server URL is configured (optimistic default)
 * - Desktop networks are always considered unmetered
 *
 * @param serverUrlProvider Function that returns the current server URL, or null if not configured
 * @param ioDispatcher Dispatcher the health-check loop polls on
 */
class JvmNetworkMonitor(
    private val serverUrlProvider: () -> String?,
    private val httpClient: HttpClient = createHealthCheckClient(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NetworkMonitor {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + appCoroutineExceptionHandler)

    override val onlineFlow: StateFlow<Boolean>
        field = MutableStateFlow(true) // Optimistic default

    // Desktop networks are always considered unmetered (WiFi/Ethernet)
    override val onUnmeteredNetworkFlow: StateFlow<Boolean>
        field = MutableStateFlow(true)

    init {
        startHealthCheckLoop()
    }

    override fun isOnline(): Boolean = onlineFlow.value

    // checkHealth is widened from private to internal solely so the cancellation contract
    // can be exercised directly in jvmTest (the polling loop is otherwise unobservable).

    private fun startHealthCheckLoop() {
        scope.launch {
            while (true) {
                checkHealth()
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    internal suspend fun checkHealth() {
        val serverUrl = serverUrlProvider()

        if (serverUrl == null) {
            // No server configured - assume online (optimistic)
            onlineFlow.value = true
            return
        }

        val isReachable =
            try {
                val response = httpClient.get("$serverUrl/healthz")
                response.status.isSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(e) { "Health check failed for $serverUrl" }
                false
            }

        if (onlineFlow.value != isReachable) {
            logger.info { "Network state changed: online=$isReachable" }
            onlineFlow.value = isReachable
        }
    }
}
