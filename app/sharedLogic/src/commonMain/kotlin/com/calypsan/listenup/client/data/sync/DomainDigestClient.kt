package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.client.data.remote.RpcChannel

/**
 * Fetches the server's per-domain [DomainDigest] for drift detection.
 *
 * A mismatch between the server digest and the client's locally-computed digest triggers a full
 * domain re-pull via [CatchUp.catchUpFromZero].
 *
 * Rides the same [SyncStreamService] channel as the firehose and catch-up paging, so drift
 * detection and the repair it triggers share one connection and one recovery policy.
 */
internal class DomainDigestClient(
    private val channel: RpcChannel<SyncStreamService>,
) {
    /**
     * Fetches the [DomainDigest] for [domain] at [cursor] from the server.
     *
     * @param domain The sync domain name (e.g. `"books"`, `"series"`).
     * @param cursor The revision up to which the digest is computed.
     */
    suspend fun fetch(
        domain: String,
        cursor: Long,
    ): AppResult<DomainDigest> = channel.call(idempotent = true) { it.digest(domain, cursor) }
}
