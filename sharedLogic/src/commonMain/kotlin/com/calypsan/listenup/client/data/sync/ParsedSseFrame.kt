package com.calypsan.listenup.client.data.sync

/**
 * A single parsed SSE frame from the [SseConnection] engine: the optional `id:` and `event:`
 * lines, and the concatenated `data:` payload the consumer decodes. Consumed by the pre-auth
 * registration-policy stream (`RegistrationPolicyStreamImpl`), which reads only [data].
 */
internal data class ParsedSseFrame(
    val id: Long?,
    val event: String?,
    val data: String,
)
