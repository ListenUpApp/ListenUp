package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * List of registered syncable domain names on the server. Returned by
 * `GET /api/v1/sync/domains`. Clients diff against their locally-tracked
 * "domains I've bootstrapped" set; new domains in the server's list trigger
 * a full pull (`?since=0`) for that domain. Same machinery handles fresh
 * client install (empty local set) and server-side new-domain rollout.
 *
 * Order: server returns names sorted lexicographically for stability.
 */
@Serializable
data class DomainList(
    @SerialName("domains") val domains: List<String>,
)
