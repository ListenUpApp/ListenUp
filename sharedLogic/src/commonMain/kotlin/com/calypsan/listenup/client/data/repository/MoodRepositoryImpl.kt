package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.client.data.local.db.BookMoodDao
import com.calypsan.listenup.client.data.local.db.MoodDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.repository.MoodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Production implementation of [MoodRepository].
 *
 * **Observation** (Room-backed, offline-first): all `observe*` calls read from Room.
 * The SSE sync engine writes server-committed state into Room via
 * [com.calypsan.listenup.client.data.sync.domains.moodsDomain] and
 * [com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain], so the
 * UI reacts without explicit network polling.
 *
 * **Mutation** (RPC-backed): `addMoodToBook`, `removeMoodFromBook` dispatch through the bounded,
 * self-healing [RpcChannel] for [MoodService]. There are no optimistic Room writes — the SSE echo
 * from the server is the single write path back into Room, keeping state consistent
 * across devices.
 *
 * @property channel the [RpcChannel] the mutation surface dispatches through; the channel folds
 *   the RPC outcome into an [AppResult] (throw → typed `Failure`, business `Failure` passthrough).
 */
internal class MoodRepositoryImpl(
    private val channel: RpcChannel<MoodService>,
    private val moodDao: MoodDao,
    private val bookMoodDao: BookMoodDao,
) : MoodRepository {
    // ── Observation (Room-backed) ─────────────────────────────────────────────

    override fun observeAllMoods(): Flow<List<Mood>> =
        moodDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeMoodsForBook(bookId: String): Flow<List<Mood>> =
        moodDao.observeForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Mood?> = moodDao.observeById(id).map { it?.toDomain() }

    override fun observeBookIdsForMood(moodId: String): Flow<List<String>> =
        bookMoodDao.observeForMood(moodId).map { rows -> rows.map { it.bookId } }

    // ── Mutation (RPC-backed) ─────────────────────────────────────────────────

    override suspend fun addMoodToBook(
        bookId: String,
        name: String,
    ): AppResult<Mood> = channel.call { it.addMoodToBook(BookId(bookId), name) }.map { it.toDomain() }

    override suspend fun removeMoodFromBook(
        bookId: String,
        moodId: String,
    ): AppResult<Unit> = channel.call { it.removeMoodFromBook(BookId(bookId), MoodId(moodId)) }
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
