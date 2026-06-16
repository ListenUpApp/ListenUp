package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.map as wireMap
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.MoodDao
import com.calypsan.listenup.client.data.remote.MoodRpcFactory
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.repository.MoodRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Production implementation of [MoodRepository].
 *
 * **Observation** (Room-backed, offline-first): all `observe*` calls read from Room.
 * The SSE sync engine writes server-committed state into Room via
 * [com.calypsan.listenup.client.data.sync.MoodSyncDomainHandler] and
 * [com.calypsan.listenup.client.data.sync.handlers.BookMoodSyncDomainHandler], so the
 * UI reacts without explicit network polling.
 *
 * **Mutation** (RPC-backed): `addMoodToBook`, `removeMoodFromBook` delegate to the
 * [MoodRpcFactory] WebSocket proxy. There are no optimistic Room writes — the SSE echo
 * from the server is the single write path back into Room, keeping state consistent
 * across devices.
 *
 * Wire [WireAppResult] values returned by the RPC service are converted to the client-layer
 * [AppResult] at this boundary, following the same pattern as [TagRepositoryImpl].
 */
class MoodRepositoryImpl(
    private val moodRpcFactory: MoodRpcFactory,
    private val moodDao: MoodDao,
) : MoodRepository {
    // ── Observation (Room-backed) ─────────────────────────────────────────────

    override fun observeAllMoods(): Flow<List<Mood>> =
        moodDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeMoodsForBook(bookId: String): Flow<List<Mood>> =
        moodDao.observeForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    // ── Mutation (RPC-backed) ─────────────────────────────────────────────────

    override suspend fun addMoodToBook(
        bookId: String,
        name: String,
    ): AppResult<Mood> =
        rpcCall {
            moodRpcFactory.get().addMoodToBook(BookId(bookId), name).wireMap { it.toDomain() }
        }

    override suspend fun removeMoodFromBook(
        bookId: String,
        moodId: String,
    ): AppResult<Unit> = rpcCallUnit { moodRpcFactory.get().removeMoodFromBook(BookId(bookId), MoodId(moodId)) }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Run an RPC call that returns a data value, converting [WireAppResult] → [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(ErrorMapper.map(e))
        }

    /**
     * Run an RPC call that returns [Unit], converting [WireAppResult] → [AppResult].
     */
    private suspend fun rpcCallUnit(block: suspend () -> WireAppResult<Unit>): AppResult<Unit> = rpcCall(block)
}

// ── Mapping ───────────────────────────────────────────────────────────────────

/**
 * Map a Room [com.calypsan.listenup.client.data.local.db.MoodEntity] to the domain [Mood].
 */
private fun com.calypsan.listenup.client.data.local.db.MoodEntity.toDomain(): Mood =
    Mood(
        id = id,
        name = name,
        slug = slug,
    )

/**
 * Map the wire [com.calypsan.listenup.api.sync.Mood] to the domain [Mood].
 */
private fun com.calypsan.listenup.api.sync.Mood.toDomain(): Mood =
    Mood(
        id = id,
        name = name,
        slug = slug,
    )
