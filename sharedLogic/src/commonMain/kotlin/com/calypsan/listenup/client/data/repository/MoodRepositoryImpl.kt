package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.dto.BookMoodMutation
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.BookMoodDao
import com.calypsan.listenup.client.data.local.db.BookMoodEntity
import com.calypsan.listenup.client.data.local.db.MoodDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.repository.MoodRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Production implementation of [MoodRepository].
 *
 * **Observation** (Room-backed, offline-first): all `observe*` calls read from Room.
 * The sync engine writes server-committed state into Room via
 * [com.calypsan.listenup.client.data.sync.domains.moodsDomain] and
 * [com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain], so the
 * UI reacts without explicit network polling.
 *
 * **Mutation**: `removeMoodFromBook` is offline-first — it tombstones the junction in Room
 * optimistically and enqueues a durable op (via [OfflineEditor.edit]) on the `book_moods` channel,
 * keyed by the `"$bookId:$moodId"` envelope id, so an edit made offline persists and replays on
 * reconnect rather than failing with a [com.calypsan.listenup.api.error.ServerConnectError]; the
 * in-flight shield defers the junction's own echo until the op drains, then it reconciles through
 * [com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain]. `addMoodToBook` is offline-first
 * when a same-name mood already exists in Room (its slug equals the server's `normalize(name)`, so
 * find-or-create resolves to that same id — the junction is mirrored and a durable
 * [BookMoodMutation.Add] enqueued); a genuinely-new mood mints its id/slug server-side and stays
 * online, dispatching through the [RpcChannel] for [MoodService].
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

    override suspend fun getMoodStats(moodId: MoodId): AppResult<FacetStats> =
        channel.call(idempotent = true) { it.getMoodStats(moodId) }

    // ── Mutation (RPC-backed) ─────────────────────────────────────────────────

    /**
     * Adding a mood to a book is find-or-create by slug server-side, so its offline-first eligibility
     * turns on whether the target mood already exists locally:
     * - **Name hit** (a live mood with [name] already in Room, case-insensitive): its slug equals the
     *   server's `normalize(name)`, so find-or-create for the same `name` resolves to THIS mood id — a
     *   false hit is impossible (two moods can't share a slug). So it's offline-first: upsert the
     *   `(bookId, moodId)` junction optimistically (revision-0, clearing any tombstone for re-add
     *   semantics) and enqueue a durable [BookMoodMutation.Add] on the `book_moods` channel, keyed by
     *   the same `"$bookId:$moodId"` envelope id the junction's mirror row uses so the in-flight shield
     *   and reconcile-on-drain align. The known mood is returned immediately.
     * - **Miss** (no same-name mood locally): a brand-new mood's id/slug are minted server-side and
     *   unknown until the echo, so it stays ONLINE via the [RpcChannel]. This also covers the rare case
     *   where the server would slug-match a *differently-named* existing mood; the echo reconciles Room.
     *
     * The client deliberately does NOT normalize slugs itself — the server's normalizer is a JVM-only
     * expect/actual, so name-match is the safe cross-platform proxy for the find-or-create identity.
     */
    override suspend fun addMoodToBook(
        bookId: String,
        name: String,
    ): AppResult<Mood> {
        // Callers pass either a slug (book-edit normalizes first) or free text (quick-add). Resolve by
        // slug FIRST so a multi-word mood is found — a name-only match misses it and drops the optimistic
        // write (no UI refresh) — then fall back to name for the free-text quick-add path.
        val existing = (moodDao.findBySlug(name) ?: moodDao.findByName(name)) ?: return onlineAddMoodToBook(bookId, name)
        return offlineEditor
            .edit(
                OutboxChannels.BookMoods,
                "$bookId:${existing.id}",
                BookMoodMutation.Add(bookId = bookId, moodId = existing.id, name = name),
                op = OpKind.Create,
            ) {
                bookMoodDao.upsert(
                    BookMoodEntity(
                        bookId = bookId,
                        moodId = existing.id,
                        syncId = Uuid.random().toString(),
                        createdAt = currentEpochMilliseconds(),
                        revision = 0,
                        deletedAt = null,
                    ),
                )
            }.map { existing.toDomain() }
    }

    /** The online find-or-create fallback for a brand-new mood; the echo reconciles Room. */
    private suspend fun onlineAddMoodToBook(
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
