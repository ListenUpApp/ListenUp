@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * The signed-in user's Hardcover connection, as the server reports it.
 *
 * The server owns this state — it holds the tokens and does the waiting during a device sign-in —
 * so there is no Room mirror: the connection is live server state, watched while the settings
 * screen shows. Matching is server state too. Nothing about it is mirrored in Room; [matchChanges] is
 * how this client's own edits reach every screen showing them.
 */
interface HardcoverRepository {
    /** The connection state: current value first, then every change. Never completes; survives reconnects. */
    fun observeConnection(): Flow<HardcoverConnection>

    /** Starts a device sign-in; see [com.calypsan.listenup.api.HardcoverService.startLink]. */
    suspend fun startLink(): AppResult<HardcoverLinkPrompt>

    /** Disconnects; see [com.calypsan.listenup.api.HardcoverService.disconnect]. */
    suspend fun disconnect(): AppResult<Unit>

    /** Chooses when ListenUp updates Hardcover; see [com.calypsan.listenup.api.HardcoverService.setShareMode]. */
    suspend fun setShareMode(mode: HardcoverShareMode): AppResult<Unit>

    /**
     * A client came to the foreground: the server pulls this user's Hardcover shelf if its last pull is
     * stale. Cheap, and safe to call on every foreground; see
     * [com.calypsan.listenup.api.HardcoverService.syncIfStale].
     */
    suspend fun syncIfStale(): AppResult<Unit>

    /**
     * Each book this client links or unlinks, as it lands, so a list or a row showing it re-reads at
     * once instead of waiting for the next sync. A failed link or unlink announces nothing.
     */
    val matchChanges: Flow<BookId>

    /**
     * When this client last linked [bookId], in this session — null if it hasn't, or has unlinked it since.
     * A link made on another device, or before the app started, has no time here: "Matched just now" is
     * something only the hand that made the match can have just seen.
     */
    fun linkedAt(bookId: BookId): Instant?

    /** Pulls the whole Hardcover shelf now and sends what is waiting; see [com.calypsan.listenup.api.HardcoverService.syncNow]. */
    suspend fun syncNow(): AppResult<Unit>

    /** Searches Hardcover's catalog; see [com.calypsan.listenup.api.HardcoverService.searchCatalog]. */
    suspend fun searchCatalog(query: String): AppResult<List<HardcoverBookCandidate>>

    /** Links [bookId] to the user's pick; see [com.calypsan.listenup.api.HardcoverService.linkBook]. */
    suspend fun linkBook(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
    ): AppResult<Unit>

    /**
     * Puts back the match a [linkBook] replaced, as it was made (by [method]) — the Undo after "Change match";
     * see [com.calypsan.listenup.api.HardcoverService.restoreMatch].
     */
    suspend fun restoreMatch(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
        method: HardcoverMatchMethod,
    ): AppResult<Unit>

    /** Removes [bookId]'s match, parking its pushes; see [com.calypsan.listenup.api.HardcoverService.unlinkBook]. */
    suspend fun unlinkBook(bookId: BookId): AppResult<Unit>

    /** The books that need a match; see [com.calypsan.listenup.api.HardcoverService.booksNeedingMatch]. */
    suspend fun booksNeedingMatch(): AppResult<List<BookId>>

    /** How [bookId] is matched; see [com.calypsan.listenup.api.HardcoverService.bookMatch]. */
    suspend fun bookMatch(bookId: BookId): AppResult<HardcoverBookMatch>
}
