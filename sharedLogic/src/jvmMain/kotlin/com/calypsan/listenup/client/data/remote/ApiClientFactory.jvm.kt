package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration.Companion.seconds

/** Finite connect timeout for streaming clients — a dead-server connect fails fast instead of hanging ~2min. */
private const val STREAMING_CONNECT_TIMEOUT_SECONDS = 10

/**
 * JVM desktop implementation of streaming HTTP client factory.
 *
 * Configures OkHttp engine with infinite timeouts for SSE/WebSocket connections:
 * - connectTimeout: 0 (infinite)
 * - readTimeout: 0 (infinite)
 * - writeTimeout: 0 (infinite)
 *
 * This prevents the default 10-second OkHttp timeouts from killing long-lived connections.
 */
internal actual suspend fun createStreamingHttpClient(
    serverUrl: ServerUrl,
    authSession: AuthSession,
    refreshAccessToken: RefreshAccessToken,
): HttpClient =
    HttpClient(OkHttp) {
        installListenUpErrorHandling()

        // Configure OkHttp engine with infinite timeouts for streaming
        engine {
            config {
                // Finite CONNECT timeout so a failed connect fails fast (~10s) instead of hanging on
                // OkHttp's default while a dead server is retried; read/write stay infinite (0 =
                // infinite) because an SSE stream holds the connection open indefinitely.
                connectTimeout(STREAMING_CONNECT_TIMEOUT_SECONDS.seconds)
                readTimeout(0.seconds)
                writeTimeout(0.seconds)
            }
        }

        install(ContentNegotiation) {
            json(appJson)
        }

        // NO HttpTimeout plugin - we're controlling timeouts at engine level

        install(Auth) {
            bearer {
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

                refreshTokens {
                    refreshAuthTokens(authSession, refreshAccessToken)
                }

                sendWithoutRequest { request -> !isAuthEndpoint(request) }
            }
        }

        defaultRequest {
            url(serverUrl.value)
            contentType(ContentType.Application.Json)
        }
    }

/**
 * JVM desktop implementation of unauthenticated streaming HTTP client factory.
 *
 * Configures OkHttp engine with infinite timeouts for SSE connections,
 * without authentication. Used for endpoints like registration status
 * that don't require auth tokens.
 */
internal actual fun createUnauthenticatedStreamingHttpClient(serverUrl: ServerUrl): HttpClient =
    HttpClient(OkHttp) {
        installListenUpErrorHandling()

        // Configure OkHttp engine with infinite timeouts for streaming
        engine {
            config {
                // Finite CONNECT timeout so a failed connect fails fast (~10s) instead of hanging on
                // OkHttp's default while a dead server is retried; read/write stay infinite (0 =
                // infinite) because an SSE stream holds the connection open indefinitely.
                connectTimeout(STREAMING_CONNECT_TIMEOUT_SECONDS.seconds)
                readTimeout(0.seconds)
                writeTimeout(0.seconds)
            }
        }

        install(ContentNegotiation) {
            json(appJson)
        }

        // NO Auth plugin - this is intentionally unauthenticated

        defaultRequest {
            url(serverUrl.value)
            contentType(ContentType.Application.Json)
        }
    }
