package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.core.currentEpochMilliseconds
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

// Window within which a repeat probe of the SAME ws URL reuses the prior success instead of opening a
// fresh kRPC WebSocket. Picker-select fires two probes ~100ms apart — findReachableUrl, then
// checkServerStatus — and opening that second socket so soon after the first hangs (the server accepts
// the upgrade but never answers the call). Kept well under the reconnection-probe cadence (5s) so
// periodic liveness checks still hit the network.
private const val PROBE_COALESCE_WINDOW_MS = 2_000L

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
 *
 * @param persistRemoteUrl Writes the server's advertised remote URL to ServerConfig storage on every successful getServerInfo (null clears it).
 * @param persistPeerVersion Seeds the peer server's version + API contract version (see
 * [com.calypsan.listenup.client.domain.repository.LocalPreferences.setPeerServerVersion]) from
 * every successful [getServerInfo]. This is the pre-auth seam: [ServerInfo] carries the server's
 * version before any authenticated request exists to carry it on response headers.
 */
internal class InstanceRepositoryImpl(
    private val getServerUrl: suspend () -> ServerUrl?,
    private val instanceRpcFactory: InstanceRpcFactory,
    private val persistRemoteUrl: suspend (String?) -> Unit,
    private val persistPeerVersion: suspend (version: String, api: String) -> Unit = { _, _ -> },
) : InstanceRepository {
    private var cachedServerInfo: ServerInfo? = null

    /** The most recent successful probe, used to coalesce rapid duplicate probes (see [callServerInfo]). */
    private var lastProbe: ProbeResult? = null

    private data class ProbeResult(
        val wsBaseUrl: String,
        val serverInfo: ServerInfo,
        val atMillis: Long,
    )

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
                persistRemoteUrl(result.data.remoteUrl)
                persistPeerVersion(result.data.version, result.data.apiVersion)
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
    private suspend fun callServerInfo(wsBaseUrl: String): AppResult<ServerInfo> {
        lastProbe?.let { recent ->
            if (recent.wsBaseUrl == wsBaseUrl &&
                currentEpochMilliseconds() - recent.atMillis < PROBE_COALESCE_WINDOW_MS
            ) {
                logger.debug {
                    "Reusing probe of $wsBaseUrl from ${currentEpochMilliseconds() - recent.atMillis}ms ago"
                }
                return AppResult.Success(recent.serverInfo)
            }
        }

        val result =
            try {
                when (val rpcResult = instanceRpcFactory.getServerInfo(wsBaseUrl)) {
                    is RpcResult.Success -> AppResult.Success(rpcResult.data)
                    is RpcResult.Failure -> AppResult.Failure(rpcResult.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(e)
            }

        if (result is AppResult.Success) {
            lastProbe = ProbeResult(wsBaseUrl, result.data, currentEpochMilliseconds())
        }
        return result
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
        cachedInstance?.takeIf { !forceRefresh }?.let { return AppResult.Success(it) }

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
