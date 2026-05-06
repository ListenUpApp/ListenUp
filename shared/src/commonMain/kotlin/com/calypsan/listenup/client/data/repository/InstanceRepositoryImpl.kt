package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.appJson
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import com.calypsan.listenup.client.core.error.AppException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json

private val logger = KotlinLogging.logger {}

private const val REQUEST_TIMEOUT_MS = 30_000L
private const val QUICK_CHECK_TIMEOUT_MS = 3_000L
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val SOCKET_TIMEOUT_MS = 30_000L

/**
 * Implementation of InstanceRepository that fetches data from the ListenUp API.
 *
 * Uses dynamic server URL resolution to support runtime URL changes (e.g., user
 * selecting a different server via MDNS discovery).
 *
 * This repository acts as a single source of truth for instance data.
 * Future enhancements could include:
 * - In-memory caching with cache invalidation
 * - Offline support with local persistence
 * - Automatic refresh on stale data
 */
class InstanceRepositoryImpl(
    private val getServerUrl: suspend () -> ServerUrl?,
) : InstanceRepository {
    /**
     * Cached instance data.
     * TODO: Add proper cache invalidation strategy when requirements are clearer.
     */
    private var cachedInstance: Instance? = null

    /**
     * Creates an HTTP client for the given server URL.
     * Each API call creates a fresh client to ensure we use the current URL.
     */
    private fun createClient(serverUrl: ServerUrl): HttpClient =
        HttpClient {
            installListenUpErrorHandling()

            install(ContentNegotiation) {
                json(appJson)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }

            defaultRequest {
                url(serverUrl.value)
            }
        }

    override suspend fun getInstance(forceRefresh: Boolean): AppResult<Instance> {
        // Return cached data if available and refresh not forced
        if (!forceRefresh && cachedInstance != null) {
            return Success(cachedInstance!!)
        }

        val serverUrl = getServerUrl()
        if (serverUrl == null) {
            logger.warn { "Cannot fetch instance: server URL not configured" }
            return Failure(IllegalStateException("Server URL not configured"))
        }

        logger.debug { "Fetching instance from ${serverUrl.value}/api/v1/instance" }

        return suspendRunCatching {
            val client = createClient(serverUrl)
            try {
                val response: ApiResponse<Instance> = client.get("/api/v1/instance").body()

                logger.debug { "Received instance response: success=${response.success}" }

                when (val result = response.toResult()) {
                    is Success -> result.data
                    is Failure -> throw AppException(result.error)
                }
            } finally {
                client.close()
            }
        }.also { result ->
            // Cache successful results
            if (result is Success) {
                cachedInstance = result.data
            }
        }
    }

    private suspend fun attemptServerVerification(currentUrl: String): AppResult<VerifiedServer> {
        val client = createUnauthenticatedClient()
        return try {
            val instanceUrl = currentUrl.trimEnd('/') + "/api/v1/instance"
            logger.debug { "Verifying server at $instanceUrl" }
            val response: ApiResponse<Instance> = client.get(instanceUrl).body()
            when (val result = response.toResult()) {
                is Success -> {
                    logger.info { "Server verified at $currentUrl" }
                    Success(VerifiedServer(result.data, currentUrl))
                }

                is Failure -> {
                    Failure(AppException(result.error))
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Failure(e)
        } finally {
            client.close()
        }
    }

    override suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer> {
        val urlsToTry = normalizeUrl(baseUrl)
        var lastException: Exception? = null
        for ((index, currentUrl) in urlsToTry.withIndex()) {
            when (val result = attemptServerVerification(currentUrl)) {
                is Success -> {
                    return result
                }

                is Failure -> {
                    val errorMessage = result.message.lowercase()
                    val isSslError =
                        errorMessage.contains("ssl") ||
                            errorMessage.contains("tls") ||
                            errorMessage.contains("handshake")
                    if (isSslError && index < urlsToTry.size - 1) {
                        logger.debug { "SSL error at $currentUrl, trying HTTP fallback" }
                        lastException = AppException(result.error)
                        continue
                    }
                    lastException = AppException(result.error)
                    break
                }
            }
        }
        return Failure(lastException ?: Exception("Server verification failed"))
    }

    /**
     * Normalize URL by adding protocol if missing.
     * Returns list of URLs to try (HTTPS first, then HTTP fallback).
     */
    private fun normalizeUrl(url: String): List<String> =
        if (url.startsWith("https://") || url.startsWith("http://")) {
            listOf(url)
        } else {
            // IP addresses (e.g. 192.168.1.1:8080) are almost certainly HTTP.
            // Try HTTP first to avoid TLS handshake timeout on plain HTTP servers.
            val isIpAddress = url.substringBefore(':').all { it.isDigit() || it == '.' }
            if (isIpAddress) {
                listOf("http://$url", "https://$url")
            } else {
                listOf("https://$url", "http://$url")
            }
        }

    /**
     * Try multiple URLs to find one that's reachable, with a quick timeout.
     *
     * Used when connecting to discovered servers where the primary URL
     * (LAN IP from mDNS) may be unreachable. Falls back to alternate URLs
     * which may include Tailscale/VPN addresses resolved via DNS.
     *
     * @param urls List of URLs to try, in priority order
     * @return The first reachable URL, or null if none work
     */
    override suspend fun findReachableUrl(urls: List<String>): String? {
        val quickClient =
            HttpClient {
                installListenUpErrorHandling()

                install(ContentNegotiation) {
                    json(appJson)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = QUICK_CHECK_TIMEOUT_MS
                    connectTimeoutMillis = QUICK_CHECK_TIMEOUT_MS
                    socketTimeoutMillis = QUICK_CHECK_TIMEOUT_MS
                }
            }

        return try {
            for (url in urls) {
                try {
                    val instanceUrl = url.trimEnd('/') + "/api/v1/instance"
                    logger.debug { "Quick-checking reachability: $instanceUrl" }
                    quickClient.get(instanceUrl)
                    logger.info { "Server reachable at $url" }
                    return url
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug { "Not reachable at $url: ${e.message}" }
                    continue
                }
            }
            null
        } finally {
            quickClient.close()
        }
    }

    /**
     * Creates an unauthenticated HTTP client for server verification.
     * Used before authentication when validating server URLs.
     */
    private fun createUnauthenticatedClient(): HttpClient =
        HttpClient {
            installListenUpErrorHandling()

            install(ContentNegotiation) {
                json(appJson)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }
}
