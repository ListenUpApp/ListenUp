package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.dto.BookMoodMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.BookMoodDao
import com.calypsan.listenup.client.data.local.db.MoodDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
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
 * **Mutation**: `removeMoodFromBook` is offline-first — it tombstones the junction in Room
 * optimistically and enqueues a durable op (via [OfflineEditor.edit]) on the `book_moods` channel,
 * keyed by the `"$bookId:$moodId"` envelope id, so an edit made offline persists and replays on
 * reconnect rather than failing with a [com.calypsan.listenup.api.error.ServerConnectError]; the
 * in-flight shield defers the junction's own echo until the op drains, then it reconciles through
 * [com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain]. `addMoodToBook` stays online
 * (find-or-create allocates a new mood's id and slug server-side); it dispatches through the
 * [RpcChannel] for [MoodService].
 *
 * @property channel the [RpcChannel] the online mutation surface dispatches through; the channel
 *   folds the RPC outcome into an [AppResult] (throw → typed `Failure`, business `Failure` passthrough).
 * @property offlineEditor the seam that composes the optimistic Room merge and the durable outbox
 *   enqueue into a single transaction for `removeMoodFromBook`.
 */
internal class MoodRepositoryImpl(
    private val channel: RpcChannel<MoodService>,
    private val moodDao: MoodDao,
    private val bookMoodDao: BookMoodDao,
    private val offlineEditor: OfflineEditor,
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

    /**
     * Adding a mood to a book is find-or-create and stays ONLINE: a brand-new mood's id and slug are
     * allocated server-side and are unknown until the echo, so it can't be mirrored optimistically.
     */
    override suspend fun addMoodToBook(
        bookId: String,
        name: String,
    ): AppResult<Mood> = channel.call { it.addMoodToBook(BookId(bookId), name) }.map { it.toDomain() }

    /**
     * Offline-first: tombstone the junction optimistically and enqueue a durable op on the
     * `book_moods` channel, keyed by the same `"$bookId:$moodId"` envelope id the junction's mirror
     * row uses so the in-flight shield and reconcile-on-drain align. Removal is idempotent server-side.
     */
    override suspend fun removeMoodFromBook(
        bookId: String,
        moodId: String,
    ): AppResult<Unit> =
        offlineEditor.edit(
            OutboxChannels.BookMoods,
            "$bookId:$moodId",
            BookMoodMutation.Remove(bookId = bookId, moodId = moodId),
            op = OpKind.Delete,
        ) {
            bookMoodDao.tombstone(bookId = bookId, moodId = moodId, deletedAt = currentEpochMilliseconds())
        }
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
