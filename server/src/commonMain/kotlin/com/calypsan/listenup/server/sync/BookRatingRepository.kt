package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.ListenerRatingSource
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
 *
 * [candidateWireId] is **client-minted and untrusted**: it arrives on `RateBookRequest` from
 * whichever device is rating, so it can be blank or can name a row that already belongs to a
 * different `(bookId, userId)` pair (by accident or by a malicious client). [BookRatingRepository]
 * guards both cases before it ever reaches [SqlSyncableRepository.upsert] — see
 * [BookRatingRepository.upsertReturningEvent].
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
 *
 * [Book_ratings.rated_at] is the server's [clock] at the row's first write, never a client-supplied
 * instant — so a rating made offline is dated when it syncs, not when the listener tapped the star,
 * mirroring every other `created_at`-shaped sync field in this codebase.
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

    /**
     * Guards every upsert that would otherwise INSERT a fresh row — no live-or-tombstoned row yet
     * exists for `(value.bookId, value.userId)` — against [value]'s client-minted `id` (see
     * [BookRatingId]'s KDoc for why it's untrusted). Refused when the candidate is blank, or when
     * [SyncableSubstrateQueries.existsById]-by-that-id is already true (it names some OTHER pair's
     * row, live or tombstoned): letting either through would either poison the table with an
     * ambiguous blank id (a later blank-candidate write for a DIFFERENT pair would silently claim it)
     * or hand a second listener's rating [BookRatingId.idAsString]-equivalence with a first listener's
     * row.
     *
     * The pre-check runs in its own short transaction ahead of the base's write (there is no shared
     * base-class hook to splice a guard INTO that transaction), so it cannot by itself close a true
     * concurrent race between two DIFFERENT pairs colliding on the same candidate: both can pass this
     * check before either has written. SQLite serializes the actual write transactions, though, so
     * whichever commits first genuinely wins the id via INSERT; the second sees `existed = true` (the
     * id now exists — just not for ITS pair) and falls into [writePayload]'s UPDATE branch, where the
     * `check` on the affected-row count — not `idx_book_ratings_id`'s UNIQUE index, which an UPDATE
     * never touches — is what turns that residual race into a loud rollback instead of a silent
     * no-op that reads back a stranger's row. See [writePayload]'s KDoc for that half.
     *
     * No existing [SyncError] subtype names "candidate collision"; [SyncError.NotFound] is reused
     * here. A refusal here is **terminal**, not retryable with the same input: `isRetryable = false`
     * matters because the candidate itself is what's wrong, so blindly resending the identical
     * request only fails again — the client's outbox dead-letters the operation rather than looping
     * it, and a new attempt (if any) must mint a fresh candidate from scratch.
     */
    override suspend fun upsertReturningEvent(
        value: BookRatingSyncPayload,
        clientOpId: String?,
        userId: String?,
    ): AppResult<Pair<BookRatingSyncPayload, SyncEvent<BookRatingSyncPayload>>> {
        val guardError =
            suspendTransaction(db) {
                val existingId =
                    db.bookRatingsQueries.selectIdByNaturalPair(value.bookId, value.userId).executeAsOneOrNull()
                when {
                    existingId != null -> {
                        null
                    }

                    value.id.isBlank() -> {
                        SyncError.NotFound(domain = domainName, entityId = value.id)
                    }

                    db.bookRatingsQueries.existsById(value.id).executeAsOne() -> {
                        SyncError.NotFound(domain = domainName, entityId = value.id)
                    }

                    else -> {
                        null
                    }
                }
            }
        if (guardError != null) return AppResult.Failure(guardError)
        return super.upsertReturningEvent(value, clientOpId, userId)
    }

    /**
     * Writes [value]'s content columns for an existing row, or inserts a fresh one.
     *
     * The update branch requires its `UPDATE` to change **exactly one row** — see the guard in
     * [upsertReturningEvent] for why this can still be zero: that guard's own "no row for this
     * pair yet" check runs in its own short transaction, so two concurrent writers for two
     * *different* `(bookId, userId)` pairs can both pass it with the SAME client-minted candidate
     * id before either commits. Both then read `existed = true` (because [SqlSyncableRepository]'s
     * `substrate.existsById` check, run inside the actual write transaction, now sees the first
     * writer's already-inserted row) and race into this branch — but the second writer's `UPDATE …
     * WHERE book_id = ? AND user_id = ?` matches nothing, because that id belongs to the FIRST
     * writer's pair, not this one. `check` on the affected-row count catches exactly that: it
     * throws, [suspendTransaction] rolls back, and nothing is emitted — a real crash instead of a
     * silent no-op that would have told the second writer their rating saved when it didn't.
     */
    override fun writePayload(
        value: BookRatingSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            val rowsChanged =
                db.bookRatingsQueries
                    .update(
                        half_stars = value.halfStars.toLong(),
                        note = value.note,
                        updated_at = now,
                        revision = rev,
                        rated_at = now,
                        client_op_id = clientOpId,
                        source = value.source.column(),
                        book_id = value.bookId,
                        user_id = value.userId,
                    ).value
            requireSingleRatingRowUpdated(rowsChanged, value.bookId, value.userId)
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
                source = value.source.column(),
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
     *
     * Resolves the natural pair to its REAL stored id itself, first — **never** falling through to
     * [BookRatingId]'s blank default candidate the way a bare `BookRatingId(bookId, userId)` would.
     * That fallback exists only for [upsertReturningEvent]'s insert path; here, with no row for the
     * pair, "" is not a candidate to mint — it is [SyncableSubstrateQueries.softDeleteById]'s literal
     * `id = ''` predicate, which would tombstone an unrelated row that happens to carry a blank id
     * (legacy data, or an id a pre-guard client once slipped through). Finding no id means finding
     * nothing to clear, full stop.
     */
    suspend fun clear(
        bookId: String,
        userId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> {
        val existingId =
            suspendTransaction<String?>(db) {
                db.bookRatingsQueries.selectIdByNaturalPair(bookId, userId).executeAsOneOrNull()
            } ?: return AppResult.Success(Unit)
        return when (
            val result = softDelete(BookRatingId(bookId, userId, existingId), clientOpId = clientOpId)
        ) {
            is AppResult.Success -> result
            is AppResult.Failure -> if (result.error is SyncError.NotFound) AppResult.Success(Unit) else result
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
            source = ratingSourceOf(source),
        )

    private companion object {
        const val SQLITE_IN_CHUNK = 900
    }
}

/**
 * Requires that a `book_ratings` content `UPDATE` changed **exactly one row** — see
 * [BookRatingRepository.writePayload]'s KDoc for the candidate-collision race this closes: the
 * base's `existed = true` can be wrong for [bookId]/[userId] specifically, even though it's
 * correct that a row now exists SOMEWHERE under that id. Throws [IllegalStateException] (rolling
 * back the open [com.calypsan.listenup.server.db.sqldelight.suspendTransaction] and emitting
 * nothing) rather than let a 0-row `UPDATE` silently read back as a successful write of someone
 * else's rating.
 *
 * `internal`, not `private`, solely so `BookRatingRepositoryTest` can drive it directly: the guard
 * in [BookRatingRepository.upsertReturningEvent] makes the failing state unreachable through the
 * public `upsert` path in a non-concurrent test — this function is the one piece of the race-close
 * that a single-threaded test CAN exercise deterministically.
 */
internal fun requireSingleRatingRowUpdated(
    rowsChanged: Long,
    bookId: String,
    userId: String,
) {
    check(rowsChanged == 1L) { "rating write for $bookId/$userId matched no row" }
}

/** `book_ratings.source` text for [ListenerRatingSource]: lower case, as the V98 CHECK spells it. */
internal fun ListenerRatingSource.column(): String = name.lowercase()

/** [ListenerRatingSource] from `book_ratings.source`; anything unrecognised is a ListenUp rating. */
internal fun ratingSourceOf(column: String): ListenerRatingSource =
    if (column ==
        ListenerRatingSource.HARDCOVER.column()
    ) {
        ListenerRatingSource.HARDCOVER
    } else {
        ListenerRatingSource.LISTENUP
    }
