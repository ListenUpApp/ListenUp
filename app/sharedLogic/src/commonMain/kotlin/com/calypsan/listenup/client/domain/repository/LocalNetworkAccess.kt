package com.calypsan.listenup.client.domain.repository

/**
 * Answers one question after a connection to a server has already failed: was it the operating
 * system's local-network privacy gate that stopped it?
 *
 * Android 17 (`ACCESS_LOCAL_NETWORK`) and iOS (Local Network privacy) both block connections to
 * addresses on the user's own network until the user allows it — and neither tells the app. A
 * blocked connect just times out, which reads exactly like a server that is switched off. The
 * connect screens ask this only once a connect has failed with a timeout or an unreachable
 * network, so a server reached over a VPN (whose interface is never gated) is never misjudged.
 *
 * Platform implementations:
 * - Android: the permission state plus a classification of the host's addresses.
 * - iOS: a short `NWConnection` probe whose unsatisfied reason says so directly (Apple TN3179).
 * - Desktop JVM and the browser: never — neither platform has the gate.
 */
interface LocalNetworkAccess {
    /**
     * True when a connection to [host]:[port] is being blocked because the user has not allowed
     * local network access. False when access is allowed, the platform has no such gate, or the
     * host is not on the local network.
     */
    suspend fun isDeniedFor(
        host: String,
        port: Int,
    ): Boolean
}
