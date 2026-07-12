package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val logger = loggerFor<ListenUpSidecar>()

/**
 * The `listenup.json` serialization boundary: a lenient [Json] configuration plus
 * throw-free serialize/parse helpers. Pretty-printed on purpose — humans read and
 * hand-edit this file; that readability is a feature, not an accident.
 */
object SidecarJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

    /** Serializes [sidecar] to pretty-printed UTF-8 JSON bytes. */
    fun serialize(sidecar: ListenUpSidecar): ByteArray =
        json.encodeToString(ListenUpSidecar.serializer(), sidecar).encodeToByteArray()

    /**
     * Parses [bytes] into a [ListenUpSidecar], or `null` on any parse failure — malformed
     * JSON, a missing required field, a type mismatch. `null` is NOT an error to the
     * caller (matching every other sidecar/metadata parser's contract): a corrupt or
     * hand-broken `listenup.json` degrades to "no ListenUp curation found", never a
     * crashed scan. `CancellationException` is always re-raised.
     */
    fun parseOrNull(bytes: ByteArray): ListenUpSidecar? =
        try {
            json.decodeFromString(ListenUpSidecar.serializer(), bytes.decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            logger.warn(e) { "unparseable listenup.json — treating as absent" }
            null
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "unparseable listenup.json — treating as absent" }
            null
        }
}
