package com.calypsan.listenup.server.push

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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
        http.post("$relayUrl/v1/send") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("tokens", Json.encodeToJsonElement(ListSerializer(RelayToken.serializer()), tokens))
                    put("payload", payloadJson)
                    collapseKey?.let { put("collapseKey", JsonPrimitive(it)) }
                },
            )
        }.body()
}
