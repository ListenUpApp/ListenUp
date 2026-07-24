package com.calypsan.listenup.api

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.UnknownError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * The canonical [Json] instance for the contract layer — the source-of-truth serialization
 * configuration used to encode and decode every `@Serializable` DTO, `AuthError`, and other
 * contract type defined in `commonMain`.
 *
 * Settings:
 * - `ignoreUnknownKeys = true` — forward-compatible with new fields added on either side.
 * - `isLenient = true` — tolerates minor wire-format variance (e.g. mixed quote styles).
 * - `prettyPrint = false` — minimize over-the-wire bytes.
 *
 * **Current use:** contract round-trip tests in `commonTest`, the kotlinx.rpc serialization
 * layer, and the server's RPC exception guard all reference this instance to guarantee that every
 * DTO and `AppError` subtype survives a full encode → decode cycle against the same configuration
 * both sides share. The server's Ktor `ContentNegotiation` install also uses it directly.
 *
 * Lives in `api/` rather than `client/core/` because the contract layer must be the dependency
 * root — `client/` code may import from `api/`, but `api/` must never import from `client/`.
 * Placing the shared serialization config here keeps that boundary structurally enforced.
 *
 * The client-side [com.calypsan.listenup.core.appJson] is a superset that adds
 * client-specific concerns (sync-stream polymorphic defaults) on top of this base configuration.
 */
@OptIn(ExperimentalSerializationApi::class)
public val contractJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        serializersModule =
            SerializersModule {
                // Tolerant reader on the POLYMORPHIC axis. `ignoreUnknownKeys` above covers
                // unknown *fields*; it does nothing for unknown *subtypes* — an unrecognised
                // `type` discriminator in a sealed hierarchy throws. Without this, a client in
                // the field fails on the error path the first time a newer server returns an
                // error family added after that client shipped. See UnknownError's KDoc.
                polymorphicDefaultDeserializer(AppError::class) { UnknownErrorDeserializer(it) }
            }
    }

/**
 * Decodes an unrecognised [AppError] family as [UnknownError], recording which family actually
 * arrived.
 *
 * Every [AppError] shares the same field names, so decoding the unknown payload *as*
 * [UnknownError] carries `code`, `message`, `correlationId` and `isRetryable` across for free.
 * The one thing the payload cannot carry is its own identity — the `type` discriminator is
 * consumed by the polymorphic machinery — so it is folded into `debugInfo` here. Without it a
 * reader knows only that *something* unknown arrived, which is the difference between an
 * actionable log line and a shrug.
 */
private class UnknownErrorDeserializer(
    private val arrivingSerialName: String?,
) : DeserializationStrategy<UnknownError> {
    override val descriptor: SerialDescriptor = UnknownError.serializer().descriptor

    override fun deserialize(decoder: Decoder): UnknownError {
        val decoded = UnknownError.serializer().deserialize(decoder)
        val name = arrivingSerialName ?: return decoded
        return decoded.copy(
            debugInfo = listOfNotNull("unknownFamily=$name", decoded.debugInfo).joinToString(" "),
        )
    }
}
