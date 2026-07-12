package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.DEFAULT_CONNECT_TIMEOUT_MS
import com.calypsan.listenup.client.data.sync.SseConnection
import com.calypsan.listenup.client.data.sync.SseEvent
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

private val logger = KotlinLogging.logger {}

/**
 * SSE implementation of [RegistrationStatusStream].
 *
 * A thin cold flow over the shared [SseConnection] engine: it inherits the bounded connect (so a
 * user on the pending-approval screen against a briefly-unreachable server never hangs silently),
 * exponential reconnect (so a mid-wait server restart doesn't lose approval events), and
 * spec-tolerant framing. Pre-auth, so it rides the unauthenticated streaming client.
 */
internal class RegistrationStatusStreamImpl(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) : RegistrationStatusStream {
    private val json = appJson

    override fun streamStatus(userId: String): Flow<StreamedRegistrationStatus> =
        SseConnection(
            urlProvider = {
                serverConfig.getServerUrl()?.let { "$it/api/v1/auth/registration-status/$userId/stream" }
            },
            streamingClientProvider = { apiClientFactory.getUnauthenticatedStreamingClient() },
            connectTimeoutMillis = connectTimeoutMillis,
        ).events().mapNotNull { event ->
            (event as? SseEvent.Frame)?.frame?.data?.let(::parseSSEEvent)
        }

    override suspend fun fetchStatus(userId: String): StreamedRegistrationStatus =
        try {
            val serverUrl =
                serverConfig.getServerUrl()
                    ?: return StreamedRegistrationStatus.Pending
            val url = "$serverUrl/api/v1/auth/registration-status/$userId"
            logger.debug { "Polling registration status (one-shot): $url" }
            val body = apiClientFactory.getUnauthenticatedStreamingClient().get(url).bodyAsText()
            val event = json.decodeFromString<RegistrationStatusEvent>(body)
            when (event.status) {
                "approved" -> StreamedRegistrationStatus.Approved
                "denied" -> StreamedRegistrationStatus.Denied(event.message)
                else -> StreamedRegistrationStatus.Pending
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never-stranded: a failed poll must not crash the waiting screen — stay pending.
            logger.warn(e) { "registration-status one-shot fetch failed; treating as pending" }
            StreamedRegistrationStatus.Pending
        }

    private fun parseSSEEvent(eventJson: String): StreamedRegistrationStatus? {
        if (eventJson.isBlank()) return null
        return try {
            logger.debug { "Processing SSE event: $eventJson" }
            val event = json.decodeFromString<RegistrationStatusEvent>(eventJson)

            when (event.status) {
                "approved" -> {
                    StreamedRegistrationStatus.Approved
                }

                "denied" -> {
                    StreamedRegistrationStatus.Denied(event.message)
                }

                "pending" -> {
                    StreamedRegistrationStatus.Pending
                }

                else -> {
                    logger.warn { "Unknown registration status: ${event.status}" }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse SSE event" }
            null
        }
    }
}
