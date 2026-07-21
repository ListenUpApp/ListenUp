package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.VersionHeaders
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.domain.version.ClientIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

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
            header(VersionHeaders.CLIENT_VERSION, clientIdentity.version)
            header(VersionHeaders.CLIENT_API, clientIdentity.apiVersion)
        }
    }
