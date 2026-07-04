package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = KotlinLogging.logger {}

/**
 * SSE implementation of [RegistrationPolicyStream].
 *
 * Connects to the server's unauthenticated registration-policy stream and emits the current
 * [RegistrationPolicy] on connect, then each change. Mirrors [RegistrationStatusStreamImpl]'s
 * hand-rolled SSE line parsing (the same shape works across all client engines).
 */
internal class RegistrationPolicyStreamImpl(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : RegistrationPolicyStream {
    private val json = appJson

    override fun streamPolicy(): Flow<RegistrationPolicy> =
        flow {
            val serverUrl =
                serverConfig.getServerUrl()
                    ?: error("Server URL not configured")

            val httpClient = apiClientFactory.getUnauthenticatedStreamingClient()
            val url = "$serverUrl/api/v1/auth/registration-policy/stream"

            logger.info { "Connecting to registration policy SSE: $url" }

            httpClient.prepareGet(url).execute { response ->
                logger.debug { "Registration-policy SSE established: ${response.status}" }

                val channel = response.bodyAsChannel()
                var eventData = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break

                    when {
                        line.isEmpty() -> {
                            if (eventData.isNotEmpty()) {
                                parsePolicy(eventData.toString())?.let { emit(it) }
                                eventData = StringBuilder()
                            }
                        }

                        line.startsWith("data: ") -> {
                            eventData.append(line.removePrefix("data: "))
                        }
                    }
                }
            }
        }

    private fun parsePolicy(eventJson: String): RegistrationPolicy? =
        try {
            json.decodeFromString(RegistrationPolicy.serializer(), eventJson)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse registration-policy SSE event: $eventJson" }
            null
        }
}
