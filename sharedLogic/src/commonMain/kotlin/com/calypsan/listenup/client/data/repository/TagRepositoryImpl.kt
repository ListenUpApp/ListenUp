package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.dto.BookTagMutation
import com.calypsan.listenup.api.dto.TagMutation
import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.BookTagDao
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.TagRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Production implementation of [TagRepository].
 *
 * **Observation** (Room-backed, offline-first): all `observe*` and `getTagBySlug` calls
 * read from Room. The SSE sync engine writes server-committed state into Room via
 * [com.calypsan.listenup.client.data.sync.domains.tagsDomain] and
 * [com.calypsan.listenup.client.data.sync.domains.bookTagsDomain], so the
 * UI reacts without explicit network polling.
 *
 * **Mutation**: offline-first where it can be mirrored, online where it can't.
 * - `renameTag`, `deleteTag`, `removeTagFromBook` write Room optimistically and enqueue a durable
 *   op (via [OfflineEditor.edit]) — `renameTag`/`deleteTag` on the `tags` channel keyed by tag id,
 *   `removeTagFromBook` on the `book_tags` channel keyed by the `"$bookId:$tagId"` envelope id — so
 *   an edit made offline persists and replays on reconnect rather than failing with a
 *   [com.calypsan.listenup.api.error.ServerConnectError]. The entity-level in-flight shield defers
 *   each row's own echo until its op drains; the authoritative state then reconciles through
 *   [com.calypsan.listenup.client.data.sync.domains.tagsDomain] /
 *   [com.calypsan.listenup.client.data.sync.domains.bookTagsDomain].
 * - `addTagToBook` is offline-first when a same-name tag already exists in Room (its slug equals the
 *   server's `normalize(name)`, so find-or-create resolves to that same id — the junction is mirrored
 *   and a durable [BookTagMutation.Add] enqueued); a genuinely-new tag mints its id/slug server-side
 *   and stays online, dispatching through the [RpcChannel] for [TagService].
 *
 * @property channel the [RpcChannel] the online mutation surface dispatches through; the channel
 *   folds the RPC outcome into an [AppResult] (throw → typed `Failure`, business `Failure` passthrough).
 * @property offlineEditor the one seam that composes the optimistic Room merge and the durable outbox
 *   enqueue into a single transaction for the offline-first surfaces.
 */
internal class TagRepositoryImpl(
    private val channel: RpcChannel<TagService>,
    private val tagDao: TagDao,
    private val bookTagDao: BookTagDao,
    private val offlineEditor: OfflineEditor,
) : TagRepository {
    // ── Observation (Room-backed) ─────────────────────────────────────────────

    override fun observeAllTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeTagsForBook(bookId: String): Flow<List<Tag>> =
        tagDao.observeForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getTagBySlug(slug: String): AppResult<Tag?> =
        try {
            val entity = tagDao.findBySlug(slug)
            AppResult.Success(entity?.toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "getTagBySlug($slug) failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }

    override fun observeById(id: String): Flow<Tag?> = tagDao.observeById(id).map { it?.toDomain() }

    override fun observeBookIdsForTag(tagId: String): Flow<List<String>> =
        bookTagDao.observeForTag(tagId).map { rows -> rows.map { it.bookId } }

    // ── Mutation (RPC-backed) ─────────────────────────────────────────────────

    /**
     * Adding a tag to a book is find-or-create by slug server-side, so its offline-first eligibility
     * turns on whether the target tag already exists locally:
     * - **Name hit** (a live tag with [name] already in Room, case-insensitive): its slug equals the
     *   server's `normalize(name)`, so the server's find-or-create for the same `name` resolves to THIS
     *   tag id — a false hit is impossible (two tags can't share a slug). So it's offline-first: upsert
     *   the `(bookId, tagId)` junction optimistically (revision-0, clearing any tombstone for re-add
     *   semantics) and enqueue a durable [BookTagMutation.Add] on the `book_tags` channel, keyed by the
     *   same `"$bookId:$tagId"` envelope id the junction's mirror row uses so the in-flight shield and
     *   reconcile-on-drain align. The known tag is returned immediately.
     * - **Miss** (no same-name tag locally): a brand-new tag's id/slug are minted server-side and unknown
     *   until the echo, so it stays ONLINE via the [RpcChannel]. This also correctly covers the rare case
     *   where the server would slug-match a *differently-named* existing tag (e.g. "sci-fi" vs "Sci-Fi"
     *   is a hit, but "SciFi" vs "Sci-Fi" is a miss); the echo reconciles Room either way.
     *
     * The client deliberately does NOT normalize slugs itself — the server's normalizer is a JVM-only
     * expect/actual, so name-match is the safe cross-platform proxy for the find-or-create identity.
     */
    override suspend fun addTagToBook(
        bookId: String,
        name: String,
    ): AppResult<Tag> {
        val existing = tagDao.findByName(name) ?: return onlineAddTagToBook(bookId, name)
        return offlineEditor
            .edit(
                OutboxChannels.BookTags,
                "$bookId:${existing.id}",
                BookTagMutation.Add(bookId = bookId, tagId = existing.id, name = name),
                op = OpKind.Create,
            ) {
                bookTagDao.upsert(
                    BookTagEntity(
                        bookId = bookId,
                        tagId = existing.id,
                        createdAt = currentEpochMilliseconds(),
                        revision = 0,
                        deletedAt = null,
                    ),
                )
            }.map { existing.toDomain() }
    }

    /** The online find-or-create fallback for a brand-new tag; the echo reconciles Room. */
    private suspend fun onlineAddTagToBook(
        bookId: String,
        name: String,
    ): AppResult<Tag> = channel.call { it.addTagToBook(BookId(bookId), name) }.map { it.toDomain() }

    /**
     * Offline-first: tombstone the junction optimistically and enqueue a durable op on the
     * `book_tags` channel, keyed by the same `"$bookId:$tagId"` envelope id the junction's mirror row
     * uses so the in-flight shield and reconcile-on-drain align. Removal is idempotent server-side.
     */
    override suspend fun removeTagFromBook(
        bookId: String,
        tagId: String,
    ): AppResult<Unit> =
        offlineEditor.edit(
            OutboxChannels.BookTags,
            "$bookId:$tagId",
            BookTagMutation.Remove(bookId = bookId, tagId = tagId),
            op = OpKind.Delete,
        ) {
            bookTagDao.tombstone(bookId = bookId, tagId = tagId, deletedAt = currentEpochMilliseconds())
        }

    /**
     * Offline-first: apply the new display name to Room and enqueue a durable op on the `tags`
     * channel keyed by the tag id. Slug and revision are left untouched — the slug never changes on
     * rename, and the tag's own echo (deferred by the in-flight shield) is the final word on
     * revision. Returns the optimistic aggregate; [TagError.NotFound] when the tag isn't in Room.
     */
    override suspend fun renameTag(
        tagId: String,
        newName: String,
    ): AppResult<Tag> {
        val renamed = (tagDao.getById(tagId) ?: return AppResult.Failure(TagError.NotFound())).copy(name = newName)
        return offlineEditor
            .edit(OutboxChannels.Tags, tagId, TagMutation.Rename(newName)) {
                tagDao.upsert(renamed)
            }.map { renamed.toDomain() }
    }

    /**
     * Offline-first: soft-delete the tag and cascade-tombstone its `book_tags` junctions (mirroring
     * the server's `deleteTag` cascade), then enqueue a durable op on the `tags` channel keyed by the
     * tag id. The tag's revision is left untouched so its own echo (deferred by the in-flight shield)
     * re-applies the authoritative tombstone on drain; the junction echoes flow through their own
     * (unshielded) domain and reconcile normally.
     */
    override suspend fun deleteTag(tagId: String): AppResult<Unit> {
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(OutboxChannels.Tags, tagId, TagMutation.Delete, op = OpKind.Delete) {
            tagDao.getById(tagId)?.let { tagDao.softDelete(id = tagId, deletedAt = now, revision = it.revision) }
            bookTagDao.tombstoneAllForTag(tagId = tagId, deletedAt = now)
        }
    }
}

// ── Mapping ───────────────────────────────────────────────────────────────────

/**
 * Map a Room [TagEntity] to the domain [Tag].
 */
private fun TagEntity.toDomain(): Tag =
    Tag(
        id = id,
        name = name,
        slug = slug,
    )

/**
 * Map the wire [com.calypsan.listenup.api.sync.Tag] to the domain [Tag].
 */
private fun com.calypsan.listenup.api.sync.Tag.toDomain(): Tag =
    Tag(
        id = id,
        name = name,
        slug = slug,
    )
