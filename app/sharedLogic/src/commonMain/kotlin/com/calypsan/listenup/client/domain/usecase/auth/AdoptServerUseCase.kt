package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl

/**
 * Makes a verified server the app's server — and, when it is a DIFFERENT server from the one the
 * local library mirrors, signs out locally first.
 *
 * Every connect path routes through here (discovered server, manually entered address, invite
 * link), so there is one place that answers "is this the same server?". Identity is the server's
 * stable instance id, not its address: the same server at a new IP keeps everything, a different
 * server behind the same address does not.
 *
 * The local sign-out is [LogoutUseCase.logoutLocally] — the same clean slate the Sign Out button
 * gives, which keeps downloads and preferences. Without it the old server's books, genres and sync
 * cursors survived the switch, the new server's events failed against them, and the library
 * showed empty.
 *
 * The comparison is against the server the local LIBRARY came from ([ServerConfig.getLibraryServerId]),
 * not the current connection: the Login page's Change Server forgets the connection first.
 *
 * An unidentified server ([instanceId] null — an invite whose server could not be verified) is
 * adopted without a wipe: guessing "different" would erase what may well be the same library.
 */
class AdoptServerUseCase(
    private val serverConfig: ServerConfig,
    private val localSignOut: suspend () -> Unit,
) {
    /** Adopts the server reachable at [url], whose stable identity is [instanceId] when known. */
    suspend operator fun invoke(
        url: String,
        instanceId: String?,
    ) {
        // The library's origin decides, not the connection — Change Server forgets the connection.
        // An install from before the origin was recorded falls back to the connection it last had.
        val libraryFrom = serverConfig.getLibraryServerId() ?: serverConfig.getConnectedServerId()
        if (libraryFrom != null && instanceId != null && libraryFrom != instanceId) localSignOut()
        serverConfig.setServerUrl(ServerUrl(url))
        if (instanceId != null) {
            serverConfig.setConnectedServerId(instanceId)
            serverConfig.setLibraryServerId(instanceId)
        }
    }
}
