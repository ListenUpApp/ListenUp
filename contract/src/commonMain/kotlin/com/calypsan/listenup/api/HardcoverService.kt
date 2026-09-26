package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * The calling user's connection to their Hardcover (hardcover.app) account. Connecting uses
 * Hardcover's device sign-in: [startLink] returns a code, the user approves it on hardcover.app, and
 * the SERVER does the waiting — [observeConnection] moves from Linking to Connected on its own, so
 * a closed app doesn't break the sign-in. Tokens live only on the server, encrypted.
 */
@Rpc
interface HardcoverService {
    /**
     * Starts a device sign-in and returns what to show the user.
     * [com.calypsan.listenup.api.error.HardcoverError.AlreadyConnected] when already connected;
     * [com.calypsan.listenup.api.error.HardcoverError.NotConfigured] when this server has no
     * Hardcover app; [com.calypsan.listenup.api.error.HardcoverError.Unavailable] when Hardcover is
     * unreachable. Starting again while Linking replaces the pending code.
     */
    suspend fun startLink(): AppResult<HardcoverLinkPrompt>

    /** The caller's connection state: the current value first, then every change. */
    fun observeConnection(): Flow<RpcEvent<HardcoverConnection>>

    /**
     * Disconnects: revokes the token with Hardcover (best effort — a failure there never keeps the
     * user connected) and forgets it. Cancels a sign-in in progress. Idempotent.
     */
    suspend fun disconnect(): AppResult<Unit>
}
