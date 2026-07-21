package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.VersionHeaders
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.ClientIdentity
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.client.call.HttpClientCall
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpSend
import kotlinx.io.IOException
import io.ktor.client.plugins.plugin
import kotlin.coroutines.cancellation.CancellationException

private const val SERVER_URL_NOT_CONFIGURED_MESSAGE = "Server URL not configured"

/** Ping interval (ms) for RPC WebSockets — detects a dead/half-open socket so calls can't hang forever. */
private const val WS_PING_INTERVAL_MS = 15_000L
private val logger = KotlinLogging.logger {}

/**
 * Functional seam between the bearer plugin and the auth contract. Implementations
 * call `AuthRepository.refreshAccessToken()`. Wired this way (rather than a direct
 * `AuthRepository` dependency) so `ApiClientFactory` does not import the auth
 * domain — and so Koin can resolve the construction-time cycle by capturing the
 * scope and calling `get<AuthRepository>()` lazily inside the lambda.
 */
typealias RefreshAccessToken = suspend () -> AppResult<com.calypsan.listenup.api.dto.auth.AuthSession>

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
 * Path prefixes for endpoints that authenticate themselves (login, register, refresh,
 * setupRoot) and must NOT carry a bearer token — the bearer plugin would otherwise try
 * to attach an expired or missing token and trigger a refresh loop. Shared with the
 * platform-specific streaming client factories so all clients agree on the prefixes.
 *
 *  - `/api/v1/auth/` — the REST surface (third-party clients, smoke tests).
 *  - `/api/rpc/public/` — the kotlinx.rpc public mount that the F+G AuthRepository
 *    talks to (login/register/refresh/setupRoot). The authed mount at `/api/rpc/authed`
 *    is intentionally NOT exempt.
 *
 * See Finding 04 D2 for the original REST exemption rationale.
 */
internal val AUTH_EXEMPT_PATH_PREFIXES = listOf("/api/v1/auth/", "/api/rpc/public")

/**
 * Returns true if [request] targets an authentication endpoint that should be exempt from
 * bearer-token attachment. Uses `Url.encodedPath.startsWith(...)` on the finalized URL
 * rather than a substring match on the full URL so paths like `/api/v1/books/author/foo`
 * cannot accidentally match.
 */
internal fun isAuthEndpoint(request: io.ktor.client.request.HttpRequestBuilder): Boolean {
    val path = request.url.build().encodedPath
    return AUTH_EXEMPT_PATH_PREFIXES.any { path.startsWith(it) }
}

/**
 * Seam for obtaining authenticated HTTP clients.
 *
 * Extracted as an interface so that tests can supply a Mokkery mock (or any
 * lightweight stand-in) instead of constructing the full [KtorApiClientFactory]
 * with live bearer-token machinery. All production call sites declare this type
 * so the DI graph can substitute a fake without touching the injection site.
 */
internal interface ApiClientFactory : RemoteCache {
    /** Authenticated client for request/response API calls. */
    suspend fun getClient(): HttpClient

    /** Unauthenticated streaming client for public SSE streams. */
    suspend fun getUnauthenticatedStreamingClient(): HttpClient

    /**
     * Invalidate ONLY the request/RPC client, leaving the long-lived unauthenticated streaming
     * client open.
     *
     * The full [invalidate] (from [RemoteCache]) closes every cached client. That is correct for a
     * server-URL change, but too broad for a reconnect sweep, which exists to refresh stale RPC
     * proxies + the regular request client, not to abort an unrelated live stream. This narrower
     * path drops the request client (so the next RPC call — and the RPC proxies that derive their
     * client from it — rebinds fresh) while the streaming client keeps streaming.
     */
    suspend fun invalidateRequestClientOnly()

    /**
     * Eagerly create and cache the authenticated client without exposing it.
     *
     * Lets cross-module startup code prime the lazy client (so the first real request doesn't pay the
     * construction cost) through the public [warmUpApiClient] seam without ever naming the Ktor
     * [HttpClient] type — the reason this whole interface is `internal`. A public method naming
     * `HttpClient` (or a public interface that does) drags the Ktor client bridge, with its
     * untranslatable `HttpClientConfig<*>.() -> Unit` receiver, onto the Swift Export surface and
     * fails the generated-bridge compile.
     */
    suspend fun warmUp()
}

/**
 * Creates the production [ApiClientFactory] from its dependencies.
 *
 * `internal` (with [ApiClientFactory]) so the Ktor [HttpClient] never reaches the Swift Export
 * surface. Cross-module end-to-end tests that need a real client (e.g. the `:server` auth E2E
 * fixture) wire it through the public [clientApiClientFactoryTestModule] Koin seam in jvmMain
 * instead of constructing the internal type directly. Production code uses the Koin `networkModule`
 * binding.
 */
internal fun createApiClientFactory(
    serverConfig: ServerConfig,
    authSession: AuthSession,
    refreshAccessToken: RefreshAccessToken,
    clientIdentity: ClientIdentity,
    onPeerVersion: suspend (version: String, api: String) -> Unit = { _, _ -> },
): ApiClientFactory =
    KtorApiClientFactory(serverConfig, authSession, refreshAccessToken, clientIdentity, onPeerVersion = onPeerVersion)

/**
 * Public seam to eagerly prime the authenticated HTTP client from outside `:sharedLogic`.
 *
 * Resolves the internal [ApiClientFactory] from Koin and calls [ApiClientFactory.warmUp], so the
 * Android app's post-auth startup can warm the cache without naming the now-internal factory or its
 * Ktor [HttpClient] (both kept off the Swift Export surface). Exposes no transport type.
 */
suspend fun warmUpApiClient() {
    org.koin.mp.KoinPlatform
        .getKoin()
        .get<ApiClientFactory>()
        .warmUp()
}

/**
 * Ktor-backed production [ApiClientFactory].
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
internal class KtorApiClientFactory(
    private val serverConfig: ServerConfig,
    private val authSession: AuthSession,
    private val refreshAccessToken: RefreshAccessToken,
    private val clientIdentity: ClientIdentity,
    /**
     * Called with a CHANGED (version, api) pair captured off the server's
     * `X-Server-Version`/`X-Server-Api` response headers — see [installPeerVersionCapture].
     * Defaults to a no-op so test call sites unrelated to version capture don't need updating.
     */
    private val onPeerVersion: suspend (version: String, api: String) -> Unit = { _, _ -> },
    /**
     * Test-only engine override. `null` (production) selects the platform-default
     * engine; tests inject a `MockEngine` so the real client configuration —
     * bearer plugin, refresh bridge, retry policy, HttpSend interceptor — can be
     * driven without a network.
     */
    private val engine: HttpClientEngine? = null,
) : ApiClientFactory {
    private val mutex = Mutex()
    private var cachedClient: HttpClient? = null
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
    override suspend fun getClient(): HttpClient =
        mutex.withLock {
            cachedClient ?: createClient().also { cachedClient = it }
        }

    override suspend fun warmUp() {
        getClient()
    }

    /**
     * Cached unauthenticated streaming HTTP client for SSE endpoints that don't require
     * authentication (e.g., the registration status stream for pending users).
     */
    override suspend fun getUnauthenticatedStreamingClient(): HttpClient =
        mutex.withLock {
            cachedUnauthenticatedStreamingClient ?: run {
                val serverUrl =
                    serverConfig.getActiveUrl()
                        ?: error(SERVER_URL_NOT_CONFIGURED_MESSAGE)
                createUnauthenticatedStreamingHttpClient(serverUrl, clientIdentity).also {
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

        val config: HttpClientConfig<*>.() -> Unit = {
            installListenUpErrorHandling()
            installPeerVersionCapture(onPeerVersion)

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

            // Keepalive for the kotlinx.rpc WebSockets (installKrpc opens its sessions over this
            // base client). Without pings, a half-open / "black-hole" socket is never detected and
            // an in-flight RPC call awaits its response forever — the contributor-merge hang. A
            // periodic ping fails the dead session so the call surfaces an error instead of spinning.
            install(WebSockets) {
                pingIntervalMillis = WS_PING_INTERVAL_MS
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
                        refreshAuthTokens(authSession, refreshAccessToken)
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
                header(VersionHeaders.CLIENT_VERSION, clientIdentity.version)
                header(VersionHeaders.CLIENT_API, clientIdentity.apiVersion)
            }
        }
        val client = engine?.let { HttpClient(it, config) } ?: HttpClient(config)

        // Install HttpSend interceptor for dynamic URL resolution and fallback.
        // Skipped for WebSocket upgrades: kotlinx.rpc opens RPC sessions over
        // `ws://`/`wss://`, and rewriting the protocol/host/port between the
        // upgrade-builder set-up and the actual handshake corrupts the request
        // (e.g. the WebSockets plugin builds an Upgrade headers preflight that
        // the rewrite invalidates). RPC callers pin the URL themselves at the
        // channel mount; HTTP fallback doesn't apply to a live WebSocket transport anyway.
        client.plugin(HttpSend).intercept { request ->
            if (request.url.protocol == URLProtocol.WS || request.url.protocol == URLProtocol.WSS) {
                return@intercept execute(request)
            }

            // Resolve the current active URL dynamically for each request
            val activeUrl = serverConfig.getActiveUrl()
            if (activeUrl != null) {
                val parsed = Url(activeUrl.value)
                request.url.protocol = parsed.protocol
                request.url.host = parsed.host
                request.url.port = parsed.port
            }

            executeWithFallback(request) { execute(it) }
        }

        return client
    }

    /**
     * Execute [request], retrying once against the fallback URL on a network error.
     */
    internal suspend fun executeWithFallback(
        request: HttpRequestBuilder,
        execute: suspend (HttpRequestBuilder) -> HttpClientCall,
    ): HttpClientCall {
        // Guard clauses keep this flat: a cancelled or non-network failure propagates immediately;
        // only a network error falls through to the single fallback retry below.
        val cause =
            try {
                return execute(request)
            } catch (e: Exception) {
                if (e is CancellationException || !isNetworkError(e)) throw e
                e
            }

        val fallbackUrl = serverConfig.switchToFallbackUrl() ?: throw cause
        logger.info { "Network error, retrying with fallback URL: ${fallbackUrl.value}" }
        val parsed = Url(fallbackUrl.value)
        request.url.protocol = parsed.protocol
        request.url.host = parsed.host
        request.url.port = parsed.port

        return retryAgainstFallback(request, cause, execute)
    }

    /**
     * Retry [request] against the already-applied fallback URL. A cancelled retry propagates; any
     * other failure surfaces the [original] network error with the retry error suppressed.
     */
    private suspend fun retryAgainstFallback(
        request: HttpRequestBuilder,
        original: Exception,
        execute: suspend (HttpRequestBuilder) -> HttpClientCall,
    ): HttpClientCall =
        try {
            execute(request)
        } catch (retryError: Exception) {
            if (retryError is CancellationException) throw retryError
            original.addSuppressed(retryError)
            throw original
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
    override suspend fun invalidate() {
        mutex.withLock {
            cachedClient?.close()
            cachedClient = null
            cachedUnauthenticatedStreamingClient?.close()
            cachedUnauthenticatedStreamingClient = null
        }
    }

    override suspend fun invalidateRequestClientOnly() {
        mutex.withLock {
            cachedClient?.close()
            cachedClient = null
            // Deliberately leave cachedUnauthenticatedStreamingClient open: the reconnect sweep
            // must not abort a live pre-auth stream (e.g. the registration-policy SSE).
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
            cachedUnauthenticatedStreamingClient?.close()
            cachedUnauthenticatedStreamingClient = null
        }
    }
}

/**
 * Platform-specific unauthenticated streaming HTTP client factory.
 *
 * Creates an HttpClient configured for long-lived SSE connections
 * without authentication. Used for endpoints that don't require auth,
 * such as registration status streaming for pending users.
 *
 * @param serverUrl Base server URL
 * @param clientIdentity Announced to the server via `X-Client-Version`/`X-Client-Api`
 * @return HttpClient with streaming configuration, no auth
 */
internal expect fun createUnauthenticatedStreamingHttpClient(
    serverUrl: ServerUrl,
    clientIdentity: ClientIdentity,
): HttpClient

/**
 * Bridges the bearer plugin's `refreshTokens { }` block to
 * `AuthRepository.refreshAccessToken()`.
 *
 *  - Success → the rotated pair was ALREADY persisted inside the single-flight refresh (C1); just
 *    return it as [BearerTokens] for the immediate retry. Persisting here again would be redundant and
 *    reopen the stale-token window this bridge used to own.
 *  - `Failure(SessionExpired | InvalidRefreshToken)` → the refresh token is dead; soft-clear the
 *    session credentials so state lands in `AuthState.SessionLapsed` (shell stays mounted,
 *    banner offers sign-in) instead of the login wall.
 *  - Any other `Failure` (transport, server unreachable, validation, internal) →
 *    preserve the auth state; returning null lets the original 401 propagate so the
 *    caller can decide how to surface the failure.
 *
 * `CancellationException` is re-raised per coroutines convention.
 */
internal suspend fun refreshAuthTokens(
    authSession: AuthSession,
    refreshAccessToken: RefreshAccessToken,
): BearerTokens? =
    try {
        when (val result = refreshAccessToken()) {
            is AppResult.Success -> {
                val session = result.data
                BearerTokens(
                    accessToken = session.accessToken.value,
                    refreshToken = session.refreshToken.value,
                )
            }

            is AppResult.Failure -> {
                when (result.error) {
                    is AuthError.SessionExpired,
                    is AuthError.InvalidRefreshToken,
                    -> {
                        logger.warn {
                            "Token refresh rejected (${result.error}), lapsing session (credentials cleared, user id kept)"
                        }
                        authSession.clearSessionCredentials()
                    }

                    else -> {
                        logger.warn { "Token refresh failed (${result.error}), preserving auth state" }
                    }
                }
                null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "Token refresh failed at the transport boundary, preserving auth state" }
        null
    }
