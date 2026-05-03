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
 * **Current use:** contract round-trip tests in `commonTest` and the kotlinx.rpc serialization
 * layer use this instance to guarantee that every DTO survives a full encode → decode cycle
 * against the same configuration both sides will eventually share.
 *
 * **Intended future use:** the server's Ktor `ContentNegotiation` install and any RPC exception
 * interceptor will reference this instance once the Kotlin server's transport layer is wired
 * (planned for a later migration phase). Nothing in the server module uses it yet.
 *
 * Lives in `api/` rather than `client/core/` because the contract layer must be the dependency
 * root — `client/` code may import from `api/`, but `api/` must never import from `client/`.
 * Placing the shared serialization config here keeps that boundary structurally enforced.
 *
 * The client-side [com.calypsan.listenup.client.core.appJson] is a superset that adds
 * client-specific concerns (SSE polymorphic defaults) on top of this base configuration.
 */
public val contractJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }
