package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.BookId
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

    /**
     * Searches Hardcover's catalog for [query] — a title, an author, or both — best match first, for
     * the user to pick when linking a book by hand. Costs one Hardcover search.
     * [com.calypsan.listenup.api.error.ValidationError] for a blank query;
     * [com.calypsan.listenup.api.error.HardcoverError.NotConnected] without a connection;
     * [com.calypsan.listenup.api.error.HardcoverError.ConnectionBroken] when it needs a reconnect;
     * [com.calypsan.listenup.api.error.HardcoverError.Unavailable] when Hardcover can't answer.
     */
    suspend fun searchCatalog(query: String): AppResult<List<HardcoverBookCandidate>>

    /**
     * Links [bookId] to Hardcover book [hcBookId], as edition [hcEditionId] when given, replacing any
     * automatic match; pushes that were waiting for a match go out. [com.calypsan.listenup.api.error.BookError.NotFound]
     * for a book the caller can't see; [com.calypsan.listenup.api.error.HardcoverError.NotConnected]
     * without a connection.
     */
    suspend fun linkBook(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
    ): AppResult<Unit>

    /**
     * Forgets [bookId]'s Hardcover match — the first half of "Change match". The book then needs a
     * match, and its pushes wait until the user links it again. Idempotent.
     * [com.calypsan.listenup.api.error.BookError.NotFound] for a book the caller can't see.
     */
    suspend fun unlinkBook(bookId: BookId): AppResult<Unit>
}
