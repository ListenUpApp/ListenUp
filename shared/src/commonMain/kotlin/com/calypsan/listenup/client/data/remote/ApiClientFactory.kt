package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.core.appJson
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.HttpSend
import kotlinx.io.IOException
import io.ktor.client.plugins.plugin

private const val SERVER_URL_NOT_CONFIGURED_MESSAGE = "Server URL not configured"
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private val logger = KotlinLogging.logger {}

/**
 * HTTP methods considered idempotent per RFC 9110 §9.2.2 — safe to retry because the server
 * guarantees the same effect whether the request is applied once or many times. POST and
 * PATCH are omitted deliberately: callers that need retry semantics for those must add
 * server-side idempotency keys rather than relying on transport retry.
 */
private val IDEMPOTENT_METHODS =
    setOf(
        HttpMethod.Get,
        HttpMethod.Head,
        HttpMethod.Put,
        HttpMethod.Delete,
        HttpMethod.Options,
    )

/**
 * Path prefix for endpoints that authenticate themselves (login, refresh, logout) and must
 * NOT carry a bearer token — the bearer plugin would otherwise try to attach an expired or
 * missing token and trigger a refresh loop. Shared with the platform-specific streaming
 * client factories so all clients agree on the prefix. See Finding 04 D2.
 */
internal const val AUTH_PATH_PREFIX = "/api/v1/auth/"

/**
 * Returns true if [request] targets an authentication endpoint that should be exempt from
 * bearer-token attachment. Uses `Url.encodedPath.startsWith(...)` on the finalized URL
 * rather than a substring match on the full URL so paths like `/api/v1/books/author/foo`
 * cannot accidentally match.
 */
internal fun isAuthEndpoint(request: io.ktor.client.request.HttpRequestBuilder): Boolean =
    request.url
        .build()
        .encodedPath
        .startsWith(AUTH_PATH_PREFIX)

/**
 * Factory for creating authenticated HTTP clients with automatic token refresh.
 *
 * Provides a single cached client instance that:
 * - Automatically adds Bearer auth headers
 * - Refreshes expired tokens on 401 responses
 * - Updates SettingsRepository with new tokens
 * - Handles concurrent refresh requests safely
 *
 * The client is lazy-initialized and cached for the lifetime of the factory.
 * Call [close] to release resources when no longer needed.
 */
class ApiClientFactory(
    private val serverConfig: ServerConfig,
    private val authSession: AuthSession,
    private val authApi: AuthApiContract,
) {
    private val mutex = Mutex()
    private var cachedClient: HttpClient? = null
    private var cachedStreamingClient: HttpClient? = null
    private var cachedUnauthenticatedStreamingClient: HttpClient? = null

    /**
     * Get or create the authenticated HTTP client for regular API calls.
     *
     * Client is cached after first creation. All requests through this client
     * will automatically include Bearer tokens and handle token refresh.
     *
     * Includes timeouts suitable for request/response API calls (30s).
     *
     * @return Configured HttpClient with auth plugin and timeouts
     */
    suspend fun getClient(): HttpClient =
        mutex.withLock {
            cachedClient ?: createClient().also { cachedClient = it }
        }

    /**
     * Create a streaming HTTP client for long-lived connections (SSE, WebSocket).
     *
     * Similar to getClient() but WITHOUT timeouts, suitable for streaming responses
     * that may stay open indefinitely.
     *
     * Platform-specific implementations configure engine-level timeouts to infinity:
     * - Android: OkHttp connect/read/write timeouts = 0 (infinite)
     * - iOS: URLSession timeouts = infinity
     *
     * Cached after first creation so SSE reconnect loops don't rebuild a full HttpClient
     * (and its auth/engine/TLS setup) on every retry — see Finding 04 D4.
     *
     * @return Configured HttpClient with auth but no timeouts
     */
    suspend fun getStreamingClient(): HttpClient =
        mutex.withLock {
            cachedStreamingClient ?: run {
                val serverUrl =
                    serverConfig.getActiveUrl()
                        ?: error(SERVER_URL_NOT_CONFIGURED_MESSAGE)
                createStreamingHttpClient(
                    serverUrl = serverUrl,
                    authSession = authSession,
                    authApi = authApi,
                ).also { cachedStreamingClient = it }
            }
        }

    /**
     * Cached unauthenticated streaming HTTP client for SSE endpoints that don't require
     * authentication (e.g., the registration status stream for pending users).
     */
    suspend fun getUnauthenticatedStreamingClient(): HttpClient =
        mutex.withLock {
            cachedUnauthenticatedStreamingClient ?: run {
                val serverUrl =
                    serverConfig.getActiveUrl()
                        ?: error(SERVER_URL_NOT_CONFIGURED_MESSAGE)
                createUnauthenticatedStreamingHttpClient(serverUrl).also {
                    cachedUnauthenticatedStreamingClient = it
                }
            }
        }

    @Suppress("ThrowsCount", "CognitiveComplexMethod")
    private suspend fun createClient(): HttpClient {
        val initialUrl =
            serverConfig.getActiveUrl()
                ?: error(SERVER_URL_NOT_CONFIGURED_MESSAGE)

        logger.info { "Creating HTTP client for server: ${initialUrl.value}" }

        val client =
            HttpClient {
                installListenUpErrorHandling()

                install(ContentNegotiation) {
                    json(appJson)
                }

                // Install HttpTimeout plugin to allow per-request timeout configuration
                // Default timeouts for regular API calls (SSE uses separate client)
                @Suppress("MagicNumber")
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                }

                // Retry idempotent requests on 5xx responses and transient IO failures.
                // POST/PATCH are never retried — callers must treat them as at-most-once
                // or implement their own idempotency keys. See Finding 04 D3.
                @Suppress("MagicNumber")
                install(HttpRequestRetry) {
                    retryIf(maxRetries = 3) { request, response ->
                        request.method in IDEMPOTENT_METHODS && response.status.value in 500..599
                    }
                    retryOnExceptionIf(maxRetries = 3) { request, cause ->
                        request.method in IDEMPOTENT_METHODS &&
                            (cause is IOException || cause is HttpRequestTimeoutException)
                    }
                    exponentialDelay(base = 2.0, maxDelayMs = 10_000L)
                }

                install(Auth) {
                    bearer {
                        // Load initial tokens from storage
                        loadTokens {
                            val access = authSession.getAccessToken()?.value
                            val refresh = authSession.getRefreshToken()?.value

                            if (access != null && refresh != null) {
                                BearerTokens(
                                    accessToken = access,
                                    refreshToken = refresh,
                                )
                            } else {
                                null
                            }
                        }

                        // Refresh tokens when receiving 401 Unauthorized
                        refreshTokens {
                            refreshAuthTokens(authSession, authApi)
                        }

                        // Send bearer for every request EXCEPT auth endpoints (login, refresh,
                        // logout) — those authenticate themselves via request body, and
                        // attaching a bearer would trigger a refresh loop. See Finding 04 D2.
                        sendWithoutRequest { request -> !isAuthEndpoint(request) }
                    }
                }

                defaultRequest {
                    url(initialUrl.value)
                    contentType(ContentType.Application.Json)
                }
            }

        // Install HttpSend interceptor for dynamic URL resolution and fallback
        client.plugin(HttpSend).intercept { request ->
            // Resolve the current active URL dynamically for each request
            val activeUrl = serverConfig.getActiveUrl()
            if (activeUrl != null) {
                val parsed = Url(activeUrl.value)
                request.url.protocol = parsed.protocol
                request.url.host = parsed.host
                request.url.port = parsed.port
            }

            try {
                execute(request)
            } catch (cause: Exception) {
                if (isNetworkError(cause)) {
                    // Try fallback URL
                    val fallbackUrl = serverConfig.switchToFallbackUrl()
                    if (fallbackUrl != null) {
                        logger.info { "Network error, retrying with fallback URL: ${fallbackUrl.value}" }
                        val parsed = Url(fallbackUrl.value)
                        request.url.protocol = parsed.protocol
                        request.url.host = parsed.host
                        request.url.port = parsed.port
                        try {
                            execute(request)
                        } catch (retryError: Exception) {
                            // Fallback also failed, preserve both errors
                            cause.addSuppressed(retryError)
                            throw cause
                        }
                    } else {
                        throw cause
                    }
                } else {
                    throw cause
                }
            }
        }

        return client
    }

    /**
     * Check if an exception is a network/connection error (not an HTTP error).
     * Only these should trigger URL fallback.
     */
    private fun isNetworkError(cause: Exception): Boolean =
        cause is kotlinx.io.IOException ||
            cause is io.ktor.client.plugins.HttpRequestTimeoutException ||
            cause.cause?.let { it is kotlinx.io.IOException } == true

    /**
     * Invalidate the cached client and create a new one.
     * Useful when server URL changes or manual client reset is needed.
     */
    suspend fun invalidate() {
        mutex.withLock {
            cachedClient?.close()
            cachedClient = null
            cachedStreamingClient?.close()
            cachedStreamingClient = null
            cachedUnauthenticatedStreamingClient?.close()
            cachedUnauthenticatedStreamingClient = null
        }
    }

    /**
     * Close the cached clients and release resources.
     * Call this when the factory is no longer needed.
     */
    suspend fun close() {
        mutex.withLock {
            cachedClient?.close()
            cachedClient = null
            cachedStreamingClient?.close()
            cachedStreamingClient = null
            cachedUnauthenticatedStreamingClient?.close()
            cachedUnauthenticatedStreamingClient = null
        }
    }
}

/**
 * Platform-specific streaming HTTP client factory.
 *
 * Creates an HttpClient configured for long-lived SSE/WebSocket connections
 * with infinite timeouts at the engine level.
 *
 * @param serverUrl Base server URL
 * @param authSession For loading auth tokens
 * @param authApi For refreshing tokens
 * @return HttpClient with streaming configuration and infinite timeouts
 */
internal expect suspend fun createStreamingHttpClient(
    serverUrl: ServerUrl,
    authSession: AuthSession,
    authApi: AuthApiContract,
): HttpClient

/**
 * Platform-specific unauthenticated streaming HTTP client factory.
 *
 * Creates an HttpClient configured for long-lived SSE connections
 * without authentication. Used for endpoints that don't require auth,
 * such as registration status streaming for pending users.
 *
 * @param serverUrl Base server URL
 * @return HttpClient with streaming configuration, no auth
 */
internal expect fun createUnauthenticatedStreamingHttpClient(serverUrl: ServerUrl): HttpClient

/**
 * Refreshes auth tokens using the provided refresh token.
 *
 * On success, saves the new tokens and returns them as [BearerTokens].
 * On failure, returns null. Only clears the auth session for definitive
 * auth rejections (HTTP 401/403).
 */
internal suspend fun refreshAuthTokens(
    authSession: AuthSession,
    authApi: AuthApiContract,
): BearerTokens? {
    // No refresh token → the user is effectively logged out. Return null so Ktor's Auth
    // plugin clears its cached bearer and the 401 reaches call sites as a signal to
    // re-authenticate. Throwing here (as the pre-W2b.4 code did via `error(...)`) instead
    // crashed the refresh pipeline and left the client in a wedged state — Finding 04 D2.
    val currentRefreshToken = authSession.getRefreshToken() ?: return null

    return try {
        val response = authApi.refresh(currentRefreshToken)

        authSession.saveAuthTokens(
            access = AccessToken(response.accessToken),
            refresh = RefreshToken(response.refreshToken),
            sessionId = response.sessionId,
            userId = response.userId,
        )

        BearerTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
        )
    } catch (e: ResponseException) {
        val status = e.response.status.value
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            logger.warn(e) { "Token refresh rejected ($status), clearing auth state" }
            authSession.clearAuthTokens()
        } else {
            logger.warn(e) { "Token refresh failed with HTTP $status, preserving auth state" }
        }
        null
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Token refresh failed due to network error, preserving auth state" }
        null
    }
}
