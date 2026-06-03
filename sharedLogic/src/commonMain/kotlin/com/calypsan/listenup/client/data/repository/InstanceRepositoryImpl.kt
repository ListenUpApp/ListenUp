package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.InstanceRpcFactory
import com.calypsan.listenup.client.data.remote.dataOrFailure
import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.toWebSocketScheme
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import com.calypsan.listenup.api.result.AppResult as RpcResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

private const val REQUEST_TIMEOUT_MS = 30_000L
private const val CONNECT_TIMEOUT_MS = 10_000L
private const val SOCKET_TIMEOUT_MS = 30_000L

/**
 * [InstanceRepository] split across two transports:
 *  - **Verification + the screen-one [getServerInfo] probe** go over the
 *    [InstanceService] kotlinx.rpc proxy ([InstanceRpcFactory]). Verification is
 *    pre-authentication — there is no saved URL yet — so the factory connects to
 *    an **explicit** candidate URL rather than reading `ServerConfig`.
 *  - **[getInstance]** stays on the legacy REST `ApiResponse<Instance>` path,
 *    retained for admin/settings consumers that read the richer [Instance]
 *    (notably `remoteUrl`) until a dedicated admin GET surface exists.
 *
 * [getServerUrl] supplies the already-saved URL for the post-connect probes.
 */
class InstanceRepositoryImpl(
    private val getServerUrl: suspend () -> ServerUrl?,
    private val instanceRpcFactory: InstanceRpcFactory,
) : InstanceRepository {
    private var cachedServerInfo: ServerInfo? = null

    override suspend fun getServerInfo(forceRefresh: Boolean): AppResult<ServerInfo> {
        if (!forceRefresh) {
            cachedServerInfo?.let { return AppResult.Success(it) }
        }

        val serverUrl = getServerUrl()
        if (serverUrl == null) {
            logger.warn { "Cannot fetch server info: server URL not configured" }
            return AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "Server URL not configured"))
        }

        return when (val result = callServerInfo(toWebSocketScheme(serverUrl.value))) {
            is AppResult.Success -> {
                cachedServerInfo = result.data
                result
            }

            is AppResult.Failure -> {
                result
            }
        }
    }

    override suspend fun verifyServer(baseUrl: String): AppResult<VerifiedServer> {
        val urlsToTry = normalizeUrl(baseUrl)
        var lastFailure: AppResult.Failure? = null
        for ((index, currentUrl) in urlsToTry.withIndex()) {
            logger.debug { "Verifying server at $currentUrl" }
            when (val result = callServerInfo(toWebSocketScheme(currentUrl))) {
                is AppResult.Success -> {
                    logger.info { "Server verified at $currentUrl" }
                    return AppResult.Success(VerifiedServer(result.data, currentUrl))
                }

                is AppResult.Failure -> {
                    val message =
                        result.error.debugInfo
                            .orEmpty()
                            .lowercase() + result.error.message.lowercase()
                    val isSslError =
                        message.contains("ssl") || message.contains("tls") || message.contains("handshake")
                    lastFailure = result
                    if (isSslError && index < urlsToTry.size - 1) {
                        logger.debug { "SSL error at $currentUrl, trying next candidate" }
                        continue
                    }
                    break
                }
            }
        }
        return lastFailure
            ?: AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "Server verification failed"))
    }

    /**
     * Try multiple URLs to find one whose public RPC mount answers.
     *
     * Used when connecting to discovered servers where the primary URL (LAN IP
     * from mDNS) may be unreachable; falls back to alternates which may include
     * Tailscale/VPN addresses.
     *
     * @param urls List of URLs to try, in priority order.
     * @return The first reachable URL, or null if none work.
     */
    override suspend fun findReachableUrl(urls: List<String>): String? {
        for (url in urls) {
            logger.debug { "Quick-checking reachability: $url" }
            when (callServerInfo(toWebSocketScheme(url))) {
                is AppResult.Success -> {
                    logger.info { "Server reachable at $url" }
                    return url
                }

                is AppResult.Failure -> {
                    continue
                }
            }
        }
        return null
    }

    /**
     * One-shot RPC probe of [wsBaseUrl]'s public mount, bridged to the client
     * [AppResult]. The proxy already returns a typed [RpcResult] carrying an
     * `AppError`, so a failure passes through without re-mapping; a thrown
     * transport exception is mapped by [Failure]. `CancellationException` is
     * re-raised per coroutines convention.
     */
    private suspend fun callServerInfo(wsBaseUrl: String): AppResult<ServerInfo> =
        try {
            when (val result = instanceRpcFactory.getServerInfo(wsBaseUrl)) {
                is RpcResult.Success -> AppResult.Success(result.data)
                is RpcResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failure(e)
        }

    /**
     * Normalize URL by adding protocol if missing.
     * Returns candidate URLs to try (IP addresses → HTTP first, else HTTPS first).
     */
    private fun normalizeUrl(url: String): List<String> =
        if (url.startsWith("https://") || url.startsWith("http://")) {
            listOf(url)
        } else {
            val isIpAddress = url.substringBefore(':').all { it.isDigit() || it == '.' }
            if (isIpAddress) {
                listOf("http://$url", "https://$url")
            } else {
                listOf("https://$url", "http://$url")
            }
        }

    // ── Legacy REST path (admin/settings only) ──────────────────────────────
    // Retained verbatim until the admin surface exposes the richer Instance
    // fields (e.g. remoteUrl) over a dedicated GET. New code uses getServerInfo().

    private var cachedInstance: Instance? = null

    override suspend fun getInstance(forceRefresh: Boolean): AppResult<Instance> {
        if (!forceRefresh && cachedInstance != null) {
            return AppResult.Success(cachedInstance!!)
        }

        val serverUrl = getServerUrl()
        if (serverUrl == null) {
            logger.warn { "Cannot fetch instance: server URL not configured" }
            return AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "Server URL not configured"))
        }

        logger.debug { "Fetching instance from ${serverUrl.value}/api/v1/instance" }

        val result =
            suspendRunCatching {
                val client = createRestClient(serverUrl)
                try {
                    val response: ApiResponse<Instance> = client.get("/api/v1/instance").body()
                    response.dataOrFailure("Failed to fetch instance")
                } finally {
                    client.close()
                }
            }.flatMap { it }

        if (result is AppResult.Success) {
            cachedInstance = result.data
        }

        return result
    }

    private fun createRestClient(serverUrl: ServerUrl): HttpClient =
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
}
