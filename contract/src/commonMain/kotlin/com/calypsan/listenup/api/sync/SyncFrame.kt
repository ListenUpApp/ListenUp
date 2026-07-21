package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One sync-firehose frame — the retired SSE `event:`/`id:`/`data:` triple folded into a wire
 * type for the RPC stream. [json] is the body exactly as the server serialized it (a domain
 * [SyncEvent] or a [SyncControl]); the client dispatcher decodes it with the domain handler's
 * serializer, so the payload model is unchanged by the transport migration.
 */
@Serializable
data class SyncFrame(
    /** Domain name (`"book"`, `"collection"`, …) or [CONTROL] for out-of-band control frames. */
    @SerialName("domain") val domain: String,
    /** Monotonic revision (the resume cursor); null for control frames. */
    val revision: Long? = null,
    /** The frame body: a domain [SyncEvent] or a [SyncControl], as JSON text. */
    val json: String,
) {
    companion object {
        /** The [domain] value for out-of-band [SyncControl] frames. */
        const val CONTROL = "control"
    }
}
