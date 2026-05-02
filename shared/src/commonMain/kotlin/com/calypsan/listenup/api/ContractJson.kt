package com.calypsan.listenup.api

import kotlinx.serialization.json.Json

/**
 * The canonical [Json] instance for the contract layer — the source-of-truth wire format
 * that both server and client MUST use to encode and decode every `@Serializable` DTO,
 * `AuthError`, and other contract type defined in `commonMain`.
 *
 * Settings:
 * - `ignoreUnknownKeys = true` — forward-compatible with new fields added on either side.
 * - `isLenient = true` — tolerates minor wire-format variance (e.g. mixed quote styles).
 * - `prettyPrint = false` — minimize over-the-wire bytes.
 *
 * The client-side [com.calypsan.listenup.client.core.appJson] is a superset that adds
 * client-specific concerns (SSE polymorphic defaults). The contract layer intentionally
 * does NOT import client symbols — it is the dependency root, not a consumer.
 *
 * Contract tests, the kotlinx.rpc serialization layer, and the server's Ktor
 * `ContentNegotiation` should all use this instance.
 */
public val contractJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }
