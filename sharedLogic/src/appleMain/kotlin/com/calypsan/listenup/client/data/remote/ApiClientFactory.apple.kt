package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.version.ClientIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

/**
 * iOS implementation of streaming HTTP client factory.
 *
 * Configures Darwin (URLSession) engine with infinite timeouts for SSE/WebSocket connections.
 *
 * This prevents URLSession's default timeouts from killing long-lived connections.
 */
internal actual suspend fun createStreamingHttpClient(
    serverUrl: ServerUrl,
    authSession: AuthSession,
    refreshAccessToken: RefreshAccessToken,
    clientIdentity: ClientIdentity,
): HttpClient =
    HttpClient(Darwin) {
        installListenUpErrorHandling()

        // Configure Darwin engine with infinite timeouts for streaming
        engine {
            configureRequest {
                // Disable timeout for streaming requests
                setTimeoutInterval(Double.POSITIVE_INFINITY)
            }

            configureSession {
                // Use background session configuration for long-lived connections
                timeoutIntervalForRequest = Double.POSITIVE_INFINITY
                timeoutIntervalForResource = Double.POSITIVE_INFINITY
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
            header("X-Client-Version", clientIdentity.version)
            header("X-Client-Api", clientIdentity.apiVersion)
        }
    }

/**
 * iOS implementation of unauthenticated streaming HTTP client factory.
 *
 * Configures Darwin (URLSession) engine with infinite timeouts for SSE connections,
 * without authentication. Used for endpoints like registration status
 * that don't require auth tokens.
 */
internal actual fun createUnauthenticatedStreamingHttpClient(
    serverUrl: ServerUrl,
    clientIdentity: ClientIdentity,
): HttpClient =
    HttpClient(Darwin) {
        installListenUpErrorHandling()

        // Configure Darwin engine with infinite timeouts for streaming
        engine {
            configureRequest {
                // Disable timeout for streaming requests
                setTimeoutInterval(Double.POSITIVE_INFINITY)
            }

            configureSession {
                // Use background session configuration for long-lived connections
                timeoutIntervalForRequest = Double.POSITIVE_INFINITY
                timeoutIntervalForResource = Double.POSITIVE_INFINITY
            }
        }

        install(ContentNegotiation) {
            json(appJson)
        }

        // NO Auth plugin - this is intentionally unauthenticated

        defaultRequest {
            url(serverUrl.value)
            contentType(ContentType.Application.Json)
            header("X-Client-Version", clientIdentity.version)
            header("X-Client-Api", clientIdentity.apiVersion)
        }
    }
