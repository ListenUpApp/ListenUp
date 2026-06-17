package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/**
 * Implementation of RegistrationStatusStream using SSE.
 *
 * Uses Ktor to connect to the server's registration status SSE endpoint
 * and emits status updates as a Flow.
 */
class RegistrationStatusStreamImpl(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : RegistrationStatusStream {
    private val json = appJson

    override fun streamStatus(userId: String): Flow<StreamedRegistrationStatus> =
        flow {
            val serverUrl =
                serverConfig.getServerUrl()
                    ?: error("Server URL not configured")

            val httpClient = apiClientFactory.getUnauthenticatedStreamingClient()
            val url = "$serverUrl/api/v1/auth/registration-status/$userId/stream"

            logger.info { "Connecting to registration status SSE: $url" }

            httpClient.prepareGet(url).execute { response ->
                logger.debug { "SSE connection established: ${response.status}" }

                val channel = response.bodyAsChannel()
                var eventData = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break

                    when {
                        line.isEmpty() -> {
                            // End of event
                            if (eventData.isNotEmpty()) {
                                val status = parseSSEEvent(eventData.toString())
                                if (status != null) {
                                    emit(status)
                                }
                                eventData = StringBuilder()
                            }
                        }

                        line.startsWith("data: ") -> {
                            eventData.append(line.removePrefix("data: "))
                        }

                        line.startsWith("event: ") -> {
                            // Event type line, handled via data parsing
                        }
                    }
                }
            }
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

    private fun parseSSEEvent(eventJson: String): StreamedRegistrationStatus? =
        try {
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse SSE event" }
            null
        }
}
