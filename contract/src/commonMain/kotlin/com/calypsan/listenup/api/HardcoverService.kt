package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
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
     * Chooses when ListenUp updates the caller's Hardcover: [HardcoverShareMode.AS_I_LISTEN] (the default)
     * or [HardcoverShareMode.FINISHED_ONLY]. Switching to Only when I finish drops the starts and progress
     * still waiting to be sent; nothing already on Hardcover changes. The choice survives a disconnect and a
     * reconnect, and every device watching [observeConnection] sees it on Connected. Idempotent.
     */
    suspend fun setShareMode(mode: HardcoverShareMode): AppResult<Unit>

    /**
     * Sends the books the caller finished in ListenUp before connecting (#1540): each read arrives on
     * Hardcover as Read with when they started and finished, filling in what Hardcover already holds and
     * never duplicating it. Returns as soon as the reads are queued; [observeConnection] shows the progress.
     * Sending again queues only what is not yet sent or queued, so it is safe to repeat.
     * [com.calypsan.listenup.api.error.HardcoverError.NotConnected] without a connection;
     * [com.calypsan.listenup.api.error.HardcoverError.ConnectionBroken] when it needs a reconnect.
     */
    suspend fun sendHistory(): AppResult<Unit>

    /**
     * "Not now" on the earlier-books offer — the card goes, and a quiet row stays while those books are
     * unsent — or dismissing the finished send's summary, for good. Idempotent.
     */
    suspend fun dismissHistory(): AppResult<Unit>

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
     * Puts back a match that a [linkBook] replaced — the Undo after "Change match": [bookId] is linked to
     * Hardcover book [hcBookId] as edition [hcEditionId], recorded as made by [method], exactly as it was
     * before. Unlike [linkBook], an ASIN, ISBN or search match stays one rather than becoming the user's pick.
     * Errors as [linkBook].
     */
    suspend fun restoreMatch(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
        method: HardcoverMatchMethod,
    ): AppResult<Unit>

    /**
     * Forgets [bookId]'s Hardcover match — the first half of "Change match". The book then needs a
     * match, and its pushes wait until the user links it again. Idempotent.
     * [com.calypsan.listenup.api.error.BookError.NotFound] for a book the caller can't see.
     */
    suspend fun unlinkBook(bookId: BookId): AppResult<Unit>

    /**
     * Pulls the caller's Hardcover shelf now — in full, so anything deleted there disappears here too —
     * and sends any pushes that are waiting. Returns as soon as the work is queued; it runs on the server.
     *
     * Errors: [com.calypsan.listenup.api.error.HardcoverError.NotConnected] without a connection,
     * [com.calypsan.listenup.api.error.HardcoverError.ConnectionBroken] when it needs a reconnect.
     */
    suspend fun syncNow(): AppResult<Unit>

    /**
     * A client came to the foreground: the server pulls what changed on the caller's Hardcover shelf if
     * the last pull is more than a couple of minutes old. Cheap and idempotent — a client calls it on
     * every foreground — and it succeeds, doing nothing, when there's no working connection. Returns
     * as soon as any pull is queued.
     */
    suspend fun syncIfStale(): AppResult<Unit>

    /**
     * The caller's books that need a match — ListenUp couldn't tell which Hardcover book they are, or the
     * user removed the match — newest first, limited to books they can still see. Their pushes wait.
     * [com.calypsan.listenup.api.error.HardcoverError.NotConnected] without a connection.
     */
    suspend fun booksNeedingMatch(): AppResult<List<BookId>>

    /**
     * How [bookId] is matched on Hardcover, for its Book Detail row. A linked book is named from
     * Hardcover's catalog when Hardcover can be asked (at most once per book per server run).
     * [com.calypsan.listenup.api.error.BookError.NotFound] for a book the caller can't see;
     * [com.calypsan.listenup.api.error.HardcoverError.NotConnected] without a connection.
     */
    suspend fun bookMatch(bookId: BookId): AppResult<HardcoverBookMatch>

    /**
     * Keeps [bookId] off Hardcover ([synced] false), or syncs it again (true), for the caller (#1541). Kept off,
     * nothing about the book is sent to Hardcover or brought in from it. Nothing on Hardcover changes, but in
     * ListenUp its queued sends go, its Hardcover reads leave Readers, and a To Read entry Hardcover added comes
     * off. Synced again, what was finished meanwhile is sent as history, and the next pull brings its Hardcover
     * reads and Want to Read back. Idempotent, and works without a connection: the choice is the listener's.
     * [com.calypsan.listenup.api.error.BookError.NotFound] for a book the caller can't see.
     */
    suspend fun setBookSynced(
        bookId: BookId,
        synced: Boolean,
    ): AppResult<Unit>

    /** The caller's books kept off Hardcover that they can still see, by title (#1541). Works without a connection. */
    suspend fun keptOffBooks(): AppResult<List<BookId>>
}
