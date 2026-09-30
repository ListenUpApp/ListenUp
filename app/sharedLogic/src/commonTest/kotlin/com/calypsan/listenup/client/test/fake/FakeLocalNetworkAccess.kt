package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.LocalNetworkAccess

/**
 * In-memory [LocalNetworkAccess]: answers [denied] for every host and records what it was asked,
 * so a test can prove both the verdict and which host and port reached the gate. Set [failure] to
 * make the gate throw instead of answering.
 */
class FakeLocalNetworkAccess(
    var denied: Boolean = false,
) : LocalNetworkAccess {
    /** Every `(host, port)` the gate was consulted for, in order. */
    val queries = mutableListOf<Pair<String, Int>>()

    /** When set, every query throws this instead of answering. */
    var failure: Throwable? = null

    override suspend fun isDeniedFor(
        host: String,
        port: Int,
    ): Boolean {
        queries += host to port
        failure?.let { throw it }
        return denied
    }
}
