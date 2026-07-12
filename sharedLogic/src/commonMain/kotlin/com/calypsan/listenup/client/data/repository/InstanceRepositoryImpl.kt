package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.remote.InstanceRpcFactory
import com.calypsan.listenup.client.data.remote.toWebSocketScheme
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import com.calypsan.listenup.api.result.AppResult as RpcResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

// Window within which a repeat probe of the SAME ws URL reuses the prior success instead of opening a
// fresh kRPC WebSocket. Picker-select fires two probes ~100ms apart — findReachableUrl, then
// checkServerStatus — and opening that second socket so soon after the first hangs (the server accepts
// the upgrade but never answers the call). Kept well under the reconnection-probe cadence (5s) so
// periodic liveness checks still hit the network.
private const val PROBE_COALESCE_WINDOW_MS = 2_000L

/**
 * [InstanceRepository] backed entirely by the [InstanceService] kotlinx.rpc proxy
 * ([InstanceRpcFactory]). Verification and the screen-one [getServerInfo] probe
 * ride the public RPC mount. Verification is pre-authentication — there is no
 * saved URL yet — so the factory connects to an **explicit** candidate URL rather
 * than reading `ServerConfig`; [getServerUrl] supplies the already-saved URL for
 * the post-connect probes.
 *
 * @param persistRemoteUrl Writes the server's advertised remote URL to ServerConfig storage on every successful getServerInfo (null clears it).
 */
internal class InstanceRepositoryImpl(
    private val getServerUrl: suspend () -> ServerUrl?,
    private val instanceRpcFactory: InstanceRpcFactory,
    private val persistRemoteUrl: suspend (String?) -> Unit,
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
}
