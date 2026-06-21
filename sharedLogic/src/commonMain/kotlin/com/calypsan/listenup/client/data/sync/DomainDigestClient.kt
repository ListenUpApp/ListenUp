package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * Fetches the server's per-domain [DomainDigest] for drift detection.
 *
 * Calls `GET /api/v1/sync/<domain>/digest?cursor=<rev>` and returns the bare
 * [DomainDigest] response as an [AppResult]. A mismatch between the server digest
 * and the client's locally-computed digest triggers a full domain re-pull (`?since=0`).
 *
 * Constructor shape mirrors [SyncCatchUpClient]: two suspend lambdas so production
 * wiring passes method references and tests pass any [HttpClient] + base URL.
 */
internal class DomainDigestClient(
    private val httpClientProvider: suspend () -> HttpClient,
    private val serverUrlProvider: suspend () -> String,
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
    ): AppResult<DomainDigest> =
        suspendRunCatching {
            val baseUrl = serverUrlProvider()
            httpClientProvider()
                .get("$baseUrl/api/v1/sync/$domain/digest?cursor=$cursor")
                .body<DomainDigest>()
        }
}
