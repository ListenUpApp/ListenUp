package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.DEFAULT_CONNECT_TIMEOUT_MS
import com.calypsan.listenup.client.data.sync.SseConnection
import com.calypsan.listenup.client.data.sync.SseEvent
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

private val logger = KotlinLogging.logger {}

/**
 * SSE implementation of [RegistrationPolicyStream].
 *
 * A thin cold flow over the shared [SseConnection] engine, so it inherits the bounded connect,
 * exponential reconnect, and spec-tolerant framing — the engine now owns reconnect, replacing the
 * bespoke `retryWhen` that used to live in `AuthSessionStore`. Emits the current [RegistrationPolicy]
 * on connect, then each change. Pre-auth, so it rides the unauthenticated streaming client.
 */
internal class RegistrationPolicyStreamImpl(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) : RegistrationPolicyStream {
    private val json = appJson

    override fun streamPolicy(): Flow<RegistrationPolicy> =
        SseConnection(
            urlProvider = {
                serverConfig.getServerUrl()?.let { "$it/api/v1/auth/registration-policy/stream" }
            },
            streamingClientProvider = { apiClientFactory.getUnauthenticatedStreamingClient() },
            connectTimeoutMillis = connectTimeoutMillis,
        ).events().mapNotNull { event ->
            (event as? SseEvent.Frame)?.frame?.data?.let(::parsePolicy)
        }

    private fun parsePolicy(eventJson: String): RegistrationPolicy? {
        if (eventJson.isBlank()) return null
        return try {
            json.decodeFromString(RegistrationPolicy.serializer(), eventJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse registration-policy SSE event: $eventJson" }
            null
        }
    }
}
