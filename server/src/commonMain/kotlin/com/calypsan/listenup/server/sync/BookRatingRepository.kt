package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.sqldelight.Book_ratings
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Natural-pair identity of a `book_ratings` row: one listener, one book. [candidateWireId] is the
 * wire id to use only when no row exists yet — see [BookMoodId] for the "existing row's id wins"
 * rule this repeats.
 */
data class BookRatingId(
    val bookId: String,
    val userId: String,
    val candidateWireId: String = "",
)

/**
 * The `book_ratings` syncable repository: one row per `(bookId, userId)`, access-filtered by book
 * exactly like [BookMoodRepository] (the [driver] carries the caller's visible-book subquery into
 * the filtered pull). Writes arrive only through `BookRatingServiceImpl`, which fixes `userId` to
 * the principal — this class trusts its callers to have done that.
 */
class BookRatingRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<BookRatingSyncPayload, BookRatingId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.BOOK_RATINGS,
        clock = clock,
    ) {
    override val BookRatingSyncPayload.id: BookRatingId get() = BookRatingId(bookId, userId, id)

    override fun idAsString(id: BookRatingId): String =
        db.bookRatingsQueries.selectIdByNaturalPair(id.bookId, id.userId).executeAsOneOrNull()
            ?: id.candidateWireId

    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.bookRatingsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.bookRatingsQueries
                    .softDeleteById(
                        revision = revision,
                        updated_at = updatedAt,
                        deleted_at = deletedAt,
                        client_op_id = clientOpId,
                        id = id,
                    ).value

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.bookRatingsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.bookRatingsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): BookRatingSyncPayload? =
        db.bookRatingsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<BookRatingSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.bookRatingsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    /** A tombstone crosses the wire ungated, so it must not name the book or the listener. */
    override fun minimizeTombstone(payload: BookRatingSyncPayload): BookRatingSyncPayload =
        payload.copy(bookId = "", userId = "", note = null)

    override fun writePayload(
        value: BookRatingSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.bookRatingsQueries.update(
                half_stars = value.halfStars.toLong(),
                note = value.note,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
                book_id = value.bookId,
                user_id = value.userId,
            )
        } else {
            db.bookRatingsQueries.insert(
                id = idAsString(BookRatingId(value.bookId, value.userId, value.id)),
                book_id = value.bookId,
                user_id = value.userId,
                half_stars = value.halfStars.toLong(),
                note = value.note,
                rated_at = now,
                created_at = now,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
            )
        }
    }

    /** Live ratings of [bookId]. */
    suspend fun findForBook(bookId: String): List<BookRatingSyncPayload> =
        suspendTransaction(
            db,
        ) {
            db.bookRatingsQueries
                .selectLiveForBook(bookId)
                .executeAsList()
                .map { it.toPayload() }
        }

    /**
     * Tombstone [userId]'s rating of [bookId]. Clearing a rating that doesn't exist succeeds —
     * the only documented failure mode of the base's [softDelete] is [SyncError.NotFound], which
     * this treats as an already-cleared no-op rather than surfacing it to the caller.
     */
    suspend fun clear(
        bookId: String,
        userId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> =
        when (val result = softDelete(BookRatingId(bookId, userId), clientOpId = clientOpId)) {
            is AppResult.Success -> {
                result
            }

            is AppResult.Failure -> {
                if (result.error is SyncError.NotFound) AppResult.Success(Unit) else result
            }
        }

    /**
     * Tombstone every live rating [userId] made — the account-deletion sweep. Each row gets its own
     * revision and `Deleted` event so every device drops it. Returns the number tombstoned.
     */
    suspend fun softDeleteAllForUser(userId: String): Int =
        suspendTransaction(db) {
            val live = db.bookRatingsQueries.selectLiveIdsForUser(userId).executeAsList()
            for (id in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                db.bookRatingsQueries.softDeleteById(
                    revision = rev,
                    updated_at = now,
                    deleted_at = now,
                    client_op_id = null,
                    id = id,
                )
                emitAfterCommit(event = SyncEvent.Deleted(id = id, revision = rev, occurredAt = now, clientOpId = null))
            }
            live.size
        }

    private fun Book_ratings.toPayload(): BookRatingSyncPayload =
        BookRatingSyncPayload(
            id = id,
            bookId = book_id,
            userId = user_id,
            halfStars = half_stars.toInt(),
            note = note,
            ratedAt = rated_at,
            updatedAt = updated_at,
            revision = revision,
            deletedAt = deleted_at,
        )

    private companion object {
        const val SQLITE_IN_CHUNK = 900
    }
}
