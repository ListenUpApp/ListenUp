package com.calypsan.listenup.client.presentation.settings

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlin.time.Instant

/**
 * In-memory [HardcoverRepository]: [connection] is the server's stream (null = no answer yet),
 * the results are scripted, and a non-null gate holds a call in flight until it is completed.
 */
internal class FakeHardcoverRepository(
    initial: HardcoverConnection? = null,
) : HardcoverRepository {
    val connection = MutableStateFlow(initial)

    var startLinkResult: AppResult<HardcoverLinkPrompt> = AppResult.Success(SAMPLE_PROMPT)
    var disconnectResult: AppResult<Unit> = AppResult.Success(Unit)
    var startLinkGate: CompletableDeferred<Unit>? = null
    var disconnectGate: CompletableDeferred<Unit>? = null

    var startLinkCalls = 0
        private set
    var disconnectCalls = 0
        private set

    var syncIfStaleCalls = 0
        private set

    override suspend fun syncIfStale(): AppResult<Unit> {
        syncIfStaleCalls++
        return AppResult.Success(Unit)
    }

    override fun observeConnection(): Flow<HardcoverConnection> = connection.filterNotNull()

    override suspend fun startLink(): AppResult<HardcoverLinkPrompt> {
        startLinkCalls++
        startLinkGate?.await()
        return startLinkResult
    }

    override suspend fun disconnect(): AppResult<Unit> {
        disconnectCalls++
        disconnectGate?.await()
        return disconnectResult
    }

    var setShareModeResult: AppResult<Unit> = AppResult.Success(Unit)
    var setShareModeGate: CompletableDeferred<Unit>? = null
    val shareModes = mutableListOf<HardcoverShareMode>()

    override suspend fun setShareMode(mode: HardcoverShareMode): AppResult<Unit> {
        shareModes += mode
        setShareModeGate?.await()
        return setShareModeResult
    }

    var sendHistoryResult: AppResult<Unit> = AppResult.Success(Unit)
    var sendHistoryGate: CompletableDeferred<Unit>? = null
    var sendHistoryCalls = 0
        private set

    override suspend fun sendHistory(): AppResult<Unit> {
        sendHistoryCalls++
        sendHistoryGate?.await()
        return sendHistoryResult
    }

    var dismissHistoryResult: AppResult<Unit> = AppResult.Success(Unit)
    var dismissHistoryGate: CompletableDeferred<Unit>? = null
    var dismissHistoryCalls = 0
        private set

    override suspend fun dismissHistory(): AppResult<Unit> {
        dismissHistoryCalls++
        dismissHistoryGate?.await()
        return dismissHistoryResult
    }

    val matchChangesFlow = MutableSharedFlow<BookId>(extraBufferCapacity = 16)
    override val matchChanges: Flow<BookId> = matchChangesFlow

    var syncNowResult: AppResult<Unit> = AppResult.Success(Unit)
    var syncNowGate: CompletableDeferred<Unit>? = null
    var syncNowCalls = 0
        private set

    var searchResult: AppResult<List<HardcoverBookCandidate>> = AppResult.Success(emptyList())
    val searches = mutableListOf<String>()

    var linkResult: AppResult<Unit> = AppResult.Success(Unit)
    var linkGate: CompletableDeferred<Unit>? = null
    val links = mutableListOf<Triple<BookId, Long, Long?>>()

    var unlinkResult: AppResult<Unit> = AppResult.Success(Unit)
    val unlinks = mutableListOf<BookId>()

    var booksNeedingMatchResult: AppResult<List<BookId>> = AppResult.Success(emptyList())
    var booksNeedingMatchCalls = 0
        private set

    var bookMatchResult: AppResult<HardcoverBookMatch> = AppResult.Success(HardcoverBookMatch.Unmatched)
    var bookMatchCalls = 0
        private set

    override suspend fun syncNow(): AppResult<Unit> {
        syncNowCalls++
        syncNowGate?.await()
        return syncNowResult
    }

    override suspend fun searchCatalog(query: String): AppResult<List<HardcoverBookCandidate>> {
        searches += query
        return searchResult
    }

    override suspend fun linkBook(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
    ): AppResult<Unit> {
        links += Triple(bookId, hcBookId, hcEditionId)
        linkGate?.await()
        return linkResult.also { if (it is AppResult.Success) matchChangesFlow.tryEmit(bookId) }
    }

    var restoreResult: AppResult<Unit> = AppResult.Success(Unit)
    val restores = mutableListOf<RestoredMatch>()

    override suspend fun restoreMatch(
        bookId: BookId,
        hcBookId: Long,
        hcEditionId: Long?,
        method: HardcoverMatchMethod,
    ): AppResult<Unit> {
        restores += RestoredMatch(bookId, hcBookId, hcEditionId, method)
        return restoreResult.also { if (it is AppResult.Success) matchChangesFlow.tryEmit(bookId) }
    }

    override suspend fun unlinkBook(bookId: BookId): AppResult<Unit> {
        unlinks += bookId
        return unlinkResult.also { if (it is AppResult.Success) matchChangesFlow.tryEmit(bookId) }
    }

    override suspend fun booksNeedingMatch(): AppResult<List<BookId>> {
        booksNeedingMatchCalls++
        return booksNeedingMatchResult
    }

    /** What [linkedAt] answers, per book: when this device last linked it. */
    val linkedAtByBook = mutableMapOf<BookId, Instant>()

    override fun linkedAt(bookId: BookId): Instant? = linkedAtByBook[bookId]

    override suspend fun bookMatch(bookId: BookId): AppResult<HardcoverBookMatch> {
        bookMatchCalls++
        return bookMatchResult
    }

    companion object {
        val SAMPLE_PROMPT =
            HardcoverLinkPrompt(
                userCode = "ABCD-1234",
                verificationUri = "https://hardcover.app/link",
                verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                expiresAt = 1_800_000_000_000L,
            )
    }
}

/** One [FakeHardcoverRepository.restoreMatch] call, as it was made. */
internal data class RestoredMatch(
    val bookId: BookId,
    val hcBookId: Long,
    val hcEditionId: Long?,
    val method: HardcoverMatchMethod,
)
