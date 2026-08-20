package com.calypsan.listenup.server.push

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Thin protocol client for the ListenUp push relay (`ListenUpApp/relay`, see its `PROTOCOL.md`).
 * Server ↔ relay is a separate wire from client ↔ server, so [RelayToken]/[RelayResult]/[RelayResponse]
 * are server-internal DTOs, not `:contract` types — they never cross the client-facing boundary.
 */
class PushRelayClient(
    private val relayUrl: String,
    private val http: HttpClient,
    /** See [PushConfig.senderToken]. Null/blank sends no header at all. */
    private val senderToken: String? = null,
) {
    /** One device's push token, tagged with the platform the relay needs to route it. */
    @Serializable
    data class RelayToken(
        @SerialName("platform")
        val platform: String,
        @SerialName("token")
        val token: String,
    )

    /** The relay's per-token verdict for a single `/v1/send` call. */
    @Serializable
    data class RelayResult(
        @SerialName("token")
        val token: String,
        @SerialName("status")
        val status: String,
    )

    /** The relay's `/v1/send` response body: one [RelayResult] per requested token. */
    @Serializable
    data class RelayResponse(
        @SerialName("results")
        val results: List<RelayResult>,
    )

    /** POSTs one send; returns per-token verdicts. Throws on transport failure (caller retries once). */
    suspend fun send(
        tokens: List<RelayToken>,
        payloadJson: JsonElement,
        collapseKey: String?,
    ): RelayResponse =
        http
            .post("$relayUrl/v1/send") {
                contentType(ContentType.Application.Json)
                // Bearer, per the relay's PROTOCOL.md. Omitted entirely when unset rather than
                // sent empty: the relay rejects a PRESENT-but-wrong credential, so an empty string
                // would turn a working push into a 401 — worse than sending nothing.
                senderToken?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(
                    buildJsonObject {
                        put("tokens", Json.encodeToJsonElement(ListSerializer(RelayToken.serializer()), tokens))
                        put("payload", payloadJson)
                        collapseKey?.let { put("collapseKey", JsonPrimitive(it)) }
                    },
                )
            }.body()
}
