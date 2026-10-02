package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.api.sync.ExternalRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.Book_external_ratings
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.TransactionLocal
import com.calypsan.listenup.server.db.sqldelight.currentTransactionLocal
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext

/**
 * Natural-pair identity of a `book_external_ratings` row: one book, one outside catalog.
 * [candidateId] is the wire id to fall back to only when no row exists yet — the same
 * "existing row's id wins" shape [BookRatingId] uses, except the candidate here is always
 * **server-minted** ([BookExternalRatingRepository.recordFetch] mints it with [Uuid.random]
 * before ever constructing the payload), never client-supplied. That is what lets this
 * repository skip [BookRatingRepository]'s collision guard entirely: a self-minted UUID
 * candidate is never blank and never already claimed by a different pair, so the race
 * [BookRatingRepository.upsertReturningEvent] guards against cannot arise here.
 */
data class BookExternalRatingId(
    val bookId: String,
    val source: ExternalRatingSource,
    val candidateId: String = "",
)

/**
 * The `book_external_ratings` syncable repository: one row per `(bookId, source)`, access-filtered
 * by book exactly like [BookRatingRepository] (the [driver] carries the caller's visible-book
 * subquery into the filtered pull — wired in a follow-up commit alongside `BookAccessPolicy`).
 *
 * The server is the sole writer. There is no outbox and no client-supplied id: every successful
 * write arrives through [recordFetch], called by `ExternalRatingsFetcher` on a match, a nightly
 * sweep, or an admin's refresh request. Per-source *health* (last success, last error) is not
 * tracked here — see `com.calypsan.listenup.server.ratings.RatingSourceSettings` for why it lives
 * in server settings instead: a row only exists once a source has succeeded at least once, so
 * row-derived health would be blind to a source that has never worked at all.
 */
class BookExternalRatingRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ExternalRatingSyncPayload, BookExternalRatingId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.BOOK_EXTERNAL_RATINGS,
        clock = clock,
    ) {
    override val ExternalRatingSyncPayload.id: BookExternalRatingId
        get() = BookExternalRatingId(bookId, source, id)

    /**
     * Deterministic lookup-or-fallback: safe to call more than once for the same [value] inside one
     * transaction (the base calls it once itself, and [writePayload]'s insert branch calls it again
     * to learn the id to write) because neither half changes between calls — the DB read reflects no
     * write yet, and [BookExternalRatingId.candidateId] is fixed by the caller before either call.
     */
    override fun idAsString(id: BookExternalRatingId): String =
        db.bookExternalRatingsQueries.selectIdByNaturalPair(id.bookId, id.source.name).executeAsOneOrNull()
            ?: id.candidateId

    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.bookExternalRatingsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.bookExternalRatingsQueries
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
                db.bookExternalRatingsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.bookExternalRatingsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): ExternalRatingSyncPayload? =
        db.bookExternalRatingsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<ExternalRatingSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.bookExternalRatingsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    /** A tombstone crosses the wire ungated, so it must not name the book. */
    override fun minimizeTombstone(payload: ExternalRatingSyncPayload): ExternalRatingSyncPayload =
        payload.copy(bookId = "")

    /**
     * Writes [value]'s content columns for an existing row, or inserts a fresh one. `enabled` is
     * always written `true`: only an enabled source is ever fetched (`ExternalRatingsFetcher` skips
     * disabled ones), so a write reaching here is proof the source is currently enabled — a fetch
     * never needs to *preserve* a prior disabled flag because it can never observe one. Toggling a
     * source off is [setSourceEnabled]'s job, not this method's.
     *
     * `region`/`fetchedAt` are server-only and not on [ExternalRatingSyncPayload] — [recordFetch]
     * threads them through an [ExternalRatingWrite] extra installed via [TransactionLocal], the same
     * pattern `BookWriteExtras` uses for `BookRepository.writePayload`.
     */
    override fun writePayload(
        value: ExternalRatingSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        val extras =
            requireNotNull(ExternalRatingWrite.current()) {
                "book_external_ratings write for ${value.bookId}/${value.source} requires an ExternalRatingWrite extra"
            }
        val enabled = if (value.enabled) 1L else 0L
        if (existed) {
            db.bookExternalRatingsQueries.update(
                average = value.average,
                count = value.count.toLong(),
                enabled = enabled,
                region = extras.region,
                fetched_at = extras.fetchedAt,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
                book_id = value.bookId,
                source = value.source.name,
            )
        } else {
            db.bookExternalRatingsQueries.insert(
                id = idAsString(BookExternalRatingId(value.bookId, value.source, value.id)),
                book_id = value.bookId,
                source = value.source.name,
                average = value.average,
                count = value.count.toLong(),
                enabled = enabled,
                region = extras.region,
                fetched_at = extras.fetchedAt,
                created_at = now,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Records a successful fetch of [source] for [bookId]: inserts a fresh row (minting its id with
     * [Uuid.random]) or updates the live/tombstoned one in place, keeping its id — the same
     * "existing row's id wins" contract [BookRatingRepository] documents, just self-minted. Bumps
     * the revision and publishes `Created`/`Updated` like any other write.
     */
    suspend fun recordFetch(
        bookId: String,
        source: ExternalRatingSource,
        average: Double,
        count: Int,
        region: String?,
        fetchedAt: Long,
    ): AppResult<ExternalRatingSyncPayload> =
        withContext(TransactionLocal(ExternalRatingWrite(region = region, fetchedAt = fetchedAt))) {
            upsert(
                ExternalRatingSyncPayload(
                    id = Uuid.random().toString(),
                    bookId = bookId,
                    source = source,
                    average = average,
                    count = count,
                    enabled = true,
                    revision = 0L,
                ),
            )
        }

    /** Live external ratings of [bookId]. */
    suspend fun findForBook(bookId: String): List<ExternalRatingSyncPayload> =
        suspendTransaction(db) {
            db.bookExternalRatingsQueries
                .selectLiveForBook(bookId)
                .executeAsList()
                .map { it.toPayload() }
        }

    /**
     * Flips `enabled` on every live row of [source] — the admin's per-source on/off switch (see the
     * plan's "disabled sources" deviation: a flag per row, not a `ServerInfo` set, is what lets an
     * offline client hide a disabled source reactively). Each row gets its own revision and its own
     * `Updated` event, the way [BookRatingRepository.softDeleteAllForUser] loops. Returns the number
     * of rows flipped.
     */
    suspend fun setSourceEnabled(
        source: ExternalRatingSource,
        enabled: Boolean,
    ): Int =
        suspendTransaction(db) {
            val live = db.bookExternalRatingsQueries.selectLiveIdsForSource(source.name).executeAsList()
            for (id in live) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                db.bookExternalRatingsQueries.setEnabledById(
                    enabled = if (enabled) 1L else 0L,
                    revision = rev,
                    updated_at = now,
                    id = id,
                )
                val payload =
                    readPayload(id)
                        ?: error("readPayload returned null immediately after setEnabledById for $id")
                emitAfterCommit(
                    event =
                        SyncEvent.Updated(
                            id = id,
                            revision = rev,
                            occurredAt = now,
                            clientOpId = null,
                            payload = payload,
                        ),
                )
            }
            live.size
        }

    /**
     * The book ids the nightly sweep should refresh next, oldest-fetched first (never-fetched
     * first of all) — see `BookExternalRatings.sq`'s `selectSweepCandidates` for the ordering.
     */
    suspend fun sweepCandidates(limit: Long): List<String> =
        suspendTransaction(db) {
            db.bookExternalRatingsQueries.selectSweepCandidates(limit).executeAsList()
        }

    /** How many live books there are — the sweep's 1/30th-a-night denominator. */
    suspend fun countLiveBooks(): Long =
        suspendTransaction(db) {
            db.bookExternalRatingsQueries.countLiveBooks().executeAsOne()
        }

    /**
     * Records that [source] was attempted for [bookId] at [at], regardless of outcome — see
     * `ExternalRatingAttempts.sq` for its two readers: the nightly sweep's ordering (without it, a
     * book that never earns a `book_external_ratings` row would sort first forever) and the
     * backfill's per-source queue. Idempotent per (book, source) — a repeat overwrites the instant.
     */
    suspend fun recordAttempt(
        bookId: String,
        source: ExternalRatingSource,
        at: Long,
    ) = suspendTransaction(db) {
        db.externalRatingAttemptsQueries.recordAttempt(book_id = bookId, source = source.name, attempted_at = at)
    }

    /** Every source that has ever attempted [bookId]. */
    suspend fun attemptedSources(bookId: String): Set<ExternalRatingSource> =
        suspendTransaction(db) {
            db.externalRatingAttemptsQueries
                .selectAttemptedSources(bookId)
                .executeAsList()
                .mapNotNull { name -> ExternalRatingSource.entries.firstOrNull { it.name == name } }
                .toSet()
        }

    /** When [source] last attempted [bookId] (epoch ms), or null when it never has. */
    suspend fun attemptedAt(
        bookId: String,
        source: ExternalRatingSource,
    ): Long? =
        suspendTransaction(db) {
            db.externalRatingAttemptsQueries.selectAttemptedAt(bookId, source.name).executeAsOneOrNull()
        }

    /**
     * Up to [limit] live books, after [after] in id order, that at least one of [sources] has never
     * attempted — see `BookExternalRatings.sq`'s `selectBooksMissingAttempt`.
     * [com.calypsan.listenup.server.ratings.ExternalRatingsBackfill]'s queue, walked a page at a time.
     * No sources, no candidates.
     */
    suspend fun booksMissingAttempt(
        sources: Set<ExternalRatingSource>,
        after: String,
        limit: Long,
    ): List<String> {
        if (sources.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            db.bookExternalRatingsQueries
                .selectBooksMissingAttempt(
                    after = after,
                    sources = sources.map { it.name },
                    source_count = sources.size.toLong(),
                    limit = limit,
                ).executeAsList()
        }
    }

    /** [bookId]'s most recently fetched source's region, or null when it has never had a live row. */
    suspend fun regionForBook(bookId: String): String? =
        suspendTransaction(db) {
            db.bookExternalRatingsQueries
                .selectRegionForBook(bookId)
                .executeAsOneOrNull()
                ?.region
        }

    private fun Book_external_ratings.toPayload(): ExternalRatingSyncPayload =
        ExternalRatingSyncPayload(
            id = id,
            bookId = book_id,
            source = ExternalRatingSource.entries.firstOrNull { it.name == source } ?: ExternalRatingSource.UNKNOWN,
            average = average,
            count = count.toInt(),
            enabled = enabled != 0L,
            revision = revision,
            deletedAt = deleted_at,
        )

    private companion object {
        const val SQLITE_IN_CHUNK = 900
    }
}

/**
 * Server-only extras a [BookExternalRatingRepository.recordFetch] write carries through the
 * coroutine context into the non-suspend [BookExternalRatingRepository.writePayload] — the same
 * [TransactionLocal] pattern `BookWriteExtras` uses for `BookRepository.writePayload`. Neither
 * field is on [ExternalRatingSyncPayload]: both are server-side bookkeeping that never crosses
 * the wire (see that payload's KDoc).
 */
internal class ExternalRatingWrite(
    val region: String?,
    val fetchedAt: Long,
) {
    companion object {
        /** The extras active on the current transaction thread, or null when none is installed. */
        fun current(): ExternalRatingWrite? = currentTransactionLocal() as? ExternalRatingWrite
    }
}
