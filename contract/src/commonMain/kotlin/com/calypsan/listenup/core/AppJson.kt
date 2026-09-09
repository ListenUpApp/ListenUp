package com.calypsan.listenup.core

import kotlinx.serialization.json.Json

/**
 * The canonical [Json] instance for the entire app. All [kotlinx.serialization] encoding and
 * decoding against wire payloads (HTTP, RPC streams, persisted operation payloads, secure storage)
 * goes through this instance — see Finding 04 D5.
 *
 * Settings:
 * - `ignoreUnknownKeys = true` — forward-compatible with server additions.
 * - `isLenient = true` — tolerates minor wire-format variance (mixed quote styles,
 *   numeric booleans) that streaming payloads occasionally produce.
 * - `prettyPrint = false` — minimize over-the-wire bytes.
 * - `coerceInputValues = true` — an unknown enum literal falls back to the property's declared
 *   default rather than throwing.
 * Consumers that need a [Json] instance should inject `get<Json>()` (via Koin) or
 * reference this value directly. File-local `Json { ... }` blocks are forbidden by the
 * rubric rule derived from Finding 04 D5.
 */
val appJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        // Same enum-value tolerance as `contractJson`, and for the same reason — see its longer
        // note. This instance is a separate hand-written config, not a derivation, so a flag added
        // there does not reach the REST, secure-storage and Room decode paths that run through here.
        coerceInputValues = true
    }
