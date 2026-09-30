package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.repository.LocalNetworkAccess

/**
 * [LocalNetworkAccess] for platforms with no local-network privacy gate — desktop JVM and the
 * browser. Nothing there can block a connection for want of a permission, so a failed connect
 * is always the server's to explain.
 */
internal object UngatedLocalNetworkAccess : LocalNetworkAccess {
    override suspend fun isDeniedFor(
        host: String,
        port: Int,
    ): Boolean = false
}
