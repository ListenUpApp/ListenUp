package com.calypsan.listenup.api

import kotlinx.serialization.json.Json

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
public val contractJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }
