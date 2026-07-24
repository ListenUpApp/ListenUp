package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.GenreMutation
import com.calypsan.listenup.api.dto.GenreSummary
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.GenreWithBookCount
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Genre repository — Room-backed reads, offline-first where the edit can be mirrored.
 *
 * Tree reads (`observeAll`, `getById`, …) come from the local Room mirror,
 * which the sync engine populates via the substrate's firehose stream and
 * [com.calypsan.listenup.client.data.sync.domains.genresDomain].
 * `bookCount` on the returned [Genre] is computed at read time via JOIN on
 * `book_genres` — there is no denormalized column.
 *
 * **Mutation:** offline-first where it can be mirrored, online where it can't.
 * - `updateGenre`, `deleteGenre` write Room optimistically and enqueue a durable op (via
 *   [OfflineEditor.edit]) on the `genres` channel keyed by genre id — so an edit made offline
 *   persists and replays on reconnect rather than failing with a
 *   [com.calypsan.listenup.api.error.ServerConnectError]. The entity-level in-flight shield defers a
 *   genre's own echo until its op drains. `deleteGenre` pre-validates the server's "no live
 *   descendants" rule locally and cascade-removes the genre's `book_genres` links.
 * - `createGenre` (server mints id/slug), `moveGenre` (subtree path/depth recompute across
 *   descendants), and `mergeGenres` (server-side junction relink) stay online — they dispatch
 *   through the [RpcChannel] for [GenreService].
 *
 * @property offlineEditor Composes the optimistic Room merge and the durable outbox enqueue into a
 *   single transaction for the offline-first surfaces.
 */
internal class GenreRepositoryImpl(
    private val dao: GenreDao,
    private val channel: RpcChannel<GenreService>,
    private val offlineEditor: OfflineEditor,
) : GenreRepository {
    // ── Observation ──────────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<Genre>> =
        dao.observeAllGenresWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<Genre> = dao.getAllGenres().map { it.toDomain(bookCount = 0) }

    override suspend fun getById(id: String): Genre? = dao.getById(id)?.toDomain(bookCount = 0)

    override suspend fun getBySlug(slug: String): Genre? = dao.getBySlug(slug)?.toDomain(bookCount = 0)

    override fun observeGenresForBook(bookId: String): Flow<List<Genre>> =
        dao.observeGenresForBook(BookId(bookId)).map { entities ->
            entities.map { it.toDomain(bookCount = 0) }
        }

    override suspend fun getGenresForBook(bookId: String): List<Genre> =
        dao.getGenresForBook(BookId(bookId)).map { it.toDomain(bookCount = 0) }

    override suspend fun getBookIdsForGenre(genreId: String): List<String> =
        dao.getBookIdsForGenre(genreId).map { it.value }

    // ── Curator admin (RPC) ──────────────────────────────────────────────────

    override suspend fun createGenre(
        name: String,
        parentId: GenreId?,
        sortOrder: Int,
    ): AppResult<GenreId> = channel.call { it.createGenre(parentId, name, sortOrder) }

    /**
     * Offline-first: apply the patch's name/sortOrder to the local genre row (the mirror carries no
     * description/color column, so those fields ride the wire for the server but have nothing to
     * mirror) and enqueue a durable op on the `genres` channel keyed by the genre id. Revision and
     * slug are left untouched — the slug never changes on update, and the genre's own echo (deferred
     * by the in-flight shield) is the final word on revision.
     */
    override suspend fun updateGenre(
        id: GenreId,
        patch: GenreUpdate,
    ): AppResult<Unit> =
        offlineEditor.edit(OutboxChannels.Genres, id.value, GenreMutation.Update(patch)) {
            dao.getById(id.value)?.let { existing ->
                dao.upsert(
                    existing.copy(
                        name = patch.name ?: existing.name,
                        sortOrder = patch.sortOrder ?: existing.sortOrder,
                        // revision + slug + updatedAt deliberately untouched.
                    ),
                )
            }
        }

    /**
     * Offline-first: pre-validate the server's "no live descendants" rule against the local tree —
     * a genre with live direct children fails with [GenreError.HasDescendants] and NOTHING is written
     * or enqueued. Otherwise soft-delete the genre and cascade-remove its `book_genres` links
     * (mirroring the server's `deleteGenre` cascade), then enqueue a durable op on the `genres`
     * channel keyed by the genre id. The genre's revision is preserved so its own echo (deferred by
     * the in-flight shield) re-applies the authoritative tombstone on drain.
     */
    override suspend fun deleteGenre(id: GenreId): AppResult<Unit> {
        if (dao.liveChildCount(id.value) > 0) {
            return AppResult.Failure(GenreError.HasDescendants(debugInfo = id.value))
        }
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(OutboxChannels.Genres, id.value, GenreMutation.Delete, op = OpKind.Delete) {
            dao.getById(id.value)?.let { dao.softDelete(id = id.value, deletedAt = now, revision = it.revision) }
            dao.deleteAllBookGenresForGenre(id.value)
        }
    }

    // TODO(offline-first): moveGenre deferred — subtree path/depth recompute across descendants is
    // structural and doesn't fit the single-entity optimistic-mirror model.
    override suspend fun moveGenre(
        id: GenreId,
        newParentId: GenreId?,
    ): AppResult<Unit> = channel.call { it.moveGenre(id, newParentId) }

    override suspend fun mergeGenres(
        source: GenreId,
        target: GenreId,
    ): AppResult<Unit> = channel.call { it.mergeGenres(source, target) }

    override suspend fun browseBooks(
        genreId: GenreId,
        includeDescendants: Boolean,
        limit: Int,
    ): AppResult<List<BookId>> = channel.call(idempotent = true) { it.browseBooks(genreId, includeDescendants, limit) }

    override suspend fun getGenreStats(
        genreId: GenreId,
        includeDescendants: Boolean,
    ): AppResult<FacetStats> = channel.call(idempotent = true) { it.getGenreStats(genreId, includeDescendants) }

    override suspend fun getGenreBySlug(slug: String): AppResult<Genre?> =
        channel.call(idempotent = true) { it.getGenreBySlug(slug) }.map { it?.toDomain() }
}

private fun GenreSummary.toDomain(): Genre =
    Genre(
        id = id.value,
        name = name,
        slug = slug,
        path = path,
        bookCount = bookCount,
    )

private fun GenreEntity.toDomain(bookCount: Int): Genre =
    Genre(
        id = id,
        name = name,
        slug = slug,
        path = path,
        bookCount = bookCount,
    )

private fun GenreWithBookCount.toDomain(): Genre =
    Genre(
        id = genre.id,
        name = genre.name,
        slug = genre.slug,
        path = genre.path,
        bookCount = bookCount,
    )
