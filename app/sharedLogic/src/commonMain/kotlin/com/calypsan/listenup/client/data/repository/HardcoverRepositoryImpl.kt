package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onSuccess
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

private const val MATCH_CHANGE_BUFFER = 16

/**
 * RPC implementation of [HardcoverRepository] over [HardcoverService].
 *
 * The connection watch is infinite by contract, so it resubscribes exactly as
 * [RegistrationPolicyStreamImpl] does: an [RpcEvent.Error] or completion is never surfaced as
 * termination, the loop backs off (capped, reset by any live emission) and resubscribes, and the
 * fresh subscription's current-value emit recovers whatever changed while disconnected — including
 * a device sign-in the server finished while the socket was down.
 */
internal class HardcoverRepositoryImpl(
    private val channel: RpcChannel<HardcoverService>,
    private val clock: Clock = Clock.System,
) : HardcoverRepository {
    private val matchChangesFlow = MutableSharedFlow<BookId>(extraBufferCapacity = MATCH_CHANGE_BUFFER)

    // In memory on purpose: "just now" is this session's knowledge, and a restart rightly forgets it.
    private val linkTimes = MutableStateFlow<Map<BookId, Instant>>(emptyMap())

    override fun linkedAt(bookId: BookId): Instant? = linkTimes.value[bookId]

    override val matchChanges: Flow<BookId> = matchChangesFlow.asSharedFlow()

    // Re-asking for a full pull is harmless: a second press only restarts the pull it already asked for.
    override suspend fun syncNow(): AppResult<Unit> = channel.call(idempotent = true) { it.syncNow() }

    override suspend fun searchCatalog(query: String): AppResult<List<HardcoverBookCandidate>> =
        channel.call(idempotent = true) { it.searchCatalog(query) }

    // Linking the same book to the same pick twice leaves one link; unlinking twice, one NEEDS_MATCH.
    override suspend fun linkBook(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
    ): AppResult<Unit> =
        channel
            .call(idempotent = true) { it.linkBook(bookId, hcBookId, hcEditionId) }
            .onSuccess {
                linkTimes.update { it + (bookId to clock.now()) }
                matchChangesFlow.tryEmit(bookId)
            }

    // Putting the same match back twice leaves it as it was. Not a fresh match, so "just now" is forgotten.
    override suspend fun restoreMatch(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
        method: HardcoverMatchMethod,
    ): AppResult<Unit> =
        channel
            .call(idempotent = true) { it.restoreMatch(bookId, hcBookId, hcEditionId, method) }
            .onSuccess {
                linkTimes.update { it - bookId }
                matchChangesFlow.tryEmit(bookId)
            }

    override suspend fun unlinkBook(bookId: BookId): AppResult<Unit> =
        channel.call(idempotent = true) { it.unlinkBook(bookId) }.onSuccess {
            linkTimes.update { it - bookId }
            matchChangesFlow.tryEmit(bookId)
        }

    override suspend fun booksNeedingMatch(): AppResult<List<BookId>> =
        channel.call(idempotent = true) { it.booksNeedingMatch() }

    override suspend fun bookMatch(bookId: BookId): AppResult<HardcoverBookMatch> =
        channel.call(idempotent = true) { it.bookMatch(bookId) }

    // Keeping a book off twice leaves it off; syncing it again twice leaves it synced.
    override suspend fun setBookSynced(
        bookId: BookId,
        synced: Boolean,
    ): AppResult<Unit> =
        channel.call(idempotent = true) { it.setBookSynced(bookId, synced) }.onSuccess { matchChangesFlow.tryEmit(bookId) }

    override suspend fun keptOffBooks(): AppResult<List<BookId>> = channel.call(idempotent = true) { it.keptOffBooks() }

    override fun observeConnection(): Flow<HardcoverConnection> =
        flow {
            var backoffMs = INITIAL_RESUBSCRIBE_DELAY_MS
            while (true) {
                channel.stream { it.observeConnection() }.collect { event ->
                    when (event) {
                        is RpcEvent.Data -> {
                            // A live emission proves the watch is healthy — reset the backoff so
                            // the next drop reconnects promptly.
                            backoffMs = INITIAL_RESUBSCRIBE_DELAY_MS
                            emit(event.value)
                        }

                        // Transport faults arrive as one Error then completion (see
                        // RpcChannel.stream); the outer loop resubscribes either way.
                        is RpcEvent.Error -> {
                            logger.warn { "Hardcover connection watch errored (${event.error.code}); resubscribing" }
                        }

                        is RpcEvent.Complete -> {
                            Unit
                        }
                    }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RESUBSCRIBE_DELAY_MS)
            }
        }

    // Not idempotent: starting again replaces the pending code, so a blind re-fire after a lost
    // response could invalidate the code the user is already entering.
    override suspend fun startLink(): AppResult<HardcoverLinkPrompt> =
        channel.call(idempotent = false) { it.startLink() }

    override suspend fun disconnect(): AppResult<Unit> = channel.call(idempotent = true) { it.disconnect() }

    // Choosing the same mode twice leaves it chosen, so a blind retry is safe.
    override suspend fun setShareMode(mode: HardcoverShareMode): AppResult<Unit> =
        channel.call(idempotent = true) { it.setShareMode(mode) }

    // A second send queues only what isn't sent or queued yet, so a blind retry is safe.
    override suspend fun sendHistory(): AppResult<Unit> = channel.call(idempotent = true) { it.sendHistory() }

    // Dismissing twice leaves it dismissed.
    override suspend fun dismissHistory(): AppResult<Unit> = channel.call(idempotent = true) { it.dismissHistory() }

    // Idempotent server-side (a stale check), so a blind retry is safe.
    override suspend fun syncIfStale(): AppResult<Unit> = channel.call(idempotent = true) { it.syncIfStale() }
}
