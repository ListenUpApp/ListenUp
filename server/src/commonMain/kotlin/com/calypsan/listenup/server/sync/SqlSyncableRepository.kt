package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tombstoned
import app.cash.sqldelight.TransactionCallbacks
import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val log = loggerFor<SqlSyncableRepository<*, *>>()

/**
 * SQLDelight twin of [SyncableRepository] — the abstract base every syncable
 * SQLDelight aggregate inherits during (and after) the Exposed → SQLDelight cutover.
 *
 * It mirrors the Exposed base's orchestration verbatim — revision bumping, the
 * existed/created/updated discrimination, timestamping, [FirehoseSuppressed]
 * suppression, the `Page`/`DomainDigest` shapes, the SHA-256 digest algorithm,
 * and the type-erasure-defeating serializer helpers — but does so over SQLDelight
 * substrate queries instead of Exposed DSL. There are **no Exposed imports here**.
 *
 * Subclasses provide:
 *  - [substrate] — the aggregate's [SyncableSubstrateQueries] (its generated
 *    SQLDelight queries wrapper, adapted to the substrate contract)
 *  - [readPayload] / [readPayloads] — aggregate read (root + children) by id
 *  - [writePayload] — aggregate write inside the open transaction
 *  - the `T.id`, `T.revisionOf()`, and (for value-class ids) [idAsString] projections
 *
 * Self-registers with [SyncRegistry] and publishes to [ChangeBus] through the
 * shared [SyncableRepo] interface. Live-tail emits are deferred to the SQLDelight
 * transaction's [TransactionCallbacks.afterCommit] (see [deferEmit]) so they fire
 * after commit, in publish order — the engine-native commit-deferral mechanism.
 */
abstract class SqlSyncableRepository<T : Any, ID : Any>(
    protected val db: ListenUpDatabase,
    protected val bus: ChangeBus,
    protected val registry: SyncRegistry,
    key: SyncDomainKey<T>,
    protected val clock: Clock = Clock.System,
) : SyncableRepo<T> {
    /** Wire name, derived from the contract-level [SyncDomainKey]. */
    override val domainName: String = key.name

    /** Serializer for [T], derived from the contract-level [SyncDomainKey]. */
    val elementSerializer: KSerializer<T> = key.serializer

    init {
        register()
    }

    /**
     * Substrate-level queries for this aggregate's root table: `existsById`,
     * `softDeleteById`, `selectIdsAboveRevision`, `selectIdRevAtMost`. The
     * subclass wires this to its generated SQLDelight queries.
     */
    protected abstract val substrate: SyncableSubstrateQueries

    /**
     * The shared SQLDelight [SqlDriver] behind [db], used **only** for the access-filtered
     * catch-up/digest path ([pullSince]/[digest] with a non-null `extraWhere`): that path
     * splices a runtime-built access subquery as raw SQL, which the generated substrate queries
     * cannot express, so it runs engine-neutrally over the driver (see [selectIdRevAccessFiltered]).
     *
     * Default `null` — an ungated domain (Tags, Moods, …) never receives an `extraWhere` and so
     * never touches the driver. An access-gated aggregate overrides this with its injected driver;
     * a non-null `extraWhere` on a domain that failed to wire one fails loud via [accessDriver].
     */
    protected open val driver: SqlDriver? = null

    /**
     * The root table the access-filtered read splices its `id IN (…)` predicate against — a
     * closed, code-controlled name, never user input. Defaults to [domainName], correct for
     * every gated aggregate whose wire domain matches its storage table.
     *
     * `collection_shares` is the sole exception: its wire domain is `collection_shares` but its
     * rows live in `collection_grants`, so [CollectionGrantRepository] overrides this. Keeping it
     * a property means the base's filtered read needs no per-domain branch.
     */
    protected open val rootTableName: String get() = domainName

    /**
     * The [driver] required by the access-filtered read path, or a loud failure if the domain
     * declared an access filter (`extraWhere != null`) without wiring a driver — a wiring bug,
     * never a runtime condition, so it fails fast rather than silently degrading.
     */
    private fun accessDriver(): SqlDriver =
        requireNotNull(driver) {
            "access-filtered read on '$domainName' requires a SqlDriver, but none was wired"
        }

    /**
     * Read the full aggregate (root row + children) for [idStr], or null if absent.
     *
     * **Called only from inside an active SQLDelight transaction** opened by the
     * base methods ([upsert], [pullSince]). Implementations issue queries directly.
     */
    protected abstract fun readPayload(idStr: String): T?

    /**
     * Batch variant of [readPayload]: hydrate many aggregates by id, returning them
     * in the same order as [idStrs] and skipping ids whose root row is absent.
     *
     * The default delegates per-id. Aggregate domains whose [readPayload] issues
     * several child queries override this to batch those reads. Called inside the
     * transaction opened by [pullSince].
     */
    protected open fun readPayloads(idStrs: List<String>): List<T> = idStrs.mapNotNull { readPayload(it) }

    /**
     * Projects a soft-deleted aggregate to its wire-safe tombstone form: identity and
     * sync-discipline fields (id, revision, deletedAt, timestamps — and, for junction
     * domains, the composite-key components) preserved; every content field blanked.
     *
     * The pull path ([pullSince]/[pullByIds]) delivers tombstones ungated so every client
     * can converge on deletions — including callers whose access filter would exclude the
     * row were it live. A tombstone must therefore carry NO content: the live firehose's
     * [SyncEvent.Deleted] already carries none, and clients apply catch-up tombstones by
     * identity + revision + deletedAt alone. Applied to every pulled item whose `deletedAt`
     * is non-null, for every caller (admins included — deleted content is useless by
     * design and one rule is safer than a per-caller branch).
     *
     * Default is identity: an ungated domain, or one whose payload is already minimal
     * (e.g. collection_books — pure junction identity), needs no override. Every domain
     * registered with an access filter in SyncRoutes' ACCESS_FILTERS map MUST override
     * this unless its payload provably carries no content beyond identity.
     */
    protected open fun minimizeTombstone(payload: T): T = payload

    /** [minimizeTombstone] applied iff [payload] is a tombstone; identity otherwise. */
    private fun minimizedIfTombstoned(payload: T): T =
        if ((payload as? Tombstoned)?.deletedAt != null) minimizeTombstone(payload) else payload

    /**
     * Write the full aggregate (root row + children) inside the open transaction.
     *
     * The base has already determined [rev] (next revision) and [now] (timestamp),
     * and resolved whether the root row [existed]. The implementation MUST:
     *   - INSERT the root row if `!existed`, with `rev`, `createdAt = now`,
     *     `updatedAt = now`, `deletedAt = null`
     *   - UPDATE the root row if `existed`, with `rev`, `updatedAt = now`,
     *     `deletedAt = null`
     *   - Replace child rows wholesale (delete-then-insert), if any
     *   - Set `clientOpId` on the root row
     *
     * Atomicity is the contract: the base opens one [suspendTransaction]; this
     * method writes everything inside it.
     *
     * [userId] is the owning user for a per-user domain; null for global domains,
     * which ignore it. User-scoped writes are deferred (see [userScoped]).
     */
    protected abstract fun writePayload(
        value: T,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    )

    /**
     * Encodes a [Page] of [T] to a JSON string using [contractJson] and the
     * concrete [elementSerializer]. Verbatim from [SyncableRepository] — pure
     * serialization, no DB access.
     */
    override fun encodePageAsJson(page: Page<T>): String {
        val json: JsonObject =
            buildJsonObject {
                putJsonArray("items") {
                    page.items.forEach { item ->
                        add(contractJson.encodeToJsonElement(elementSerializer, item))
                    }
                }
                put("nextCursor", page.nextCursor)
                put("hasMore", page.hasMore)
            }
        return contractJson.encodeToString(JsonElement.serializer(), json)
    }

    /**
     * Encodes a [SyncEvent] to a JSON string using [contractJson] and the concrete
     * [elementSerializer]. Verbatim from [SyncableRepository] — pure serialization,
     * no DB access.
     */
    @Suppress("UNCHECKED_CAST")
    override fun encodeSyncEventAsJson(event: SyncEvent<*>): String =
        contractJson.encodeToString(SyncEvent.serializer(elementSerializer), event as SyncEvent<T>)

    protected abstract val T.id: ID

    /** Subclass-provided projection of the DTO's revision. */
    protected abstract fun T.revisionOf(): Long

    /**
     * `true` for a per-user domain whose root table carries a `user_id` column:
     * every write records the owning user and every read/digest filters by it.
     * Default `false` is a global domain (Tags, Moods) — the `userId` argument is
     * ignored and the global substrate path is taken unchanged.
     *
     * When `true`, [pullSince] and [digest] route through the user-scoped substrate
     * variants ([SyncableSubstrateQueries.selectIdsAboveRevisionForUser] /
     * [SyncableSubstrateQueries.selectIdRevAtMostForUser]) with a required non-null
     * [userId], reproducing the `AND user_id = ?` predicate the Exposed base appends
     * for a [UserScopedSyncableTable]. Shelf/ShelfBook are the first such aggregates;
     * the playback/listening domains reuse this path.
     */
    protected open val userScoped: Boolean = false

    /**
     * Increment the global revision counter and return its new value.
     *
     * Two-statement form (`bumpRevision` then `readRevision`) because SQLDelight's
     * SQLite dialect does not support `RETURNING`. The two statements are atomic
     * because the base always calls this inside the caller's open
     * [suspendTransaction] block — identical end-state to the Exposed base's
     * single `UPDATE … RETURNING`.
     */
    protected fun nextRevision(): Long {
        db.substrateQueries.bumpRevision()
        return db.substrateQueries.readRevision().executeAsOne()
    }

    /**
     * Insert or update [value], bumping the global revision counter and publishing
     * a [SyncEvent.Created] or [SyncEvent.Updated] to [ChangeBus].
     *
     * Created/Updated discrimination is based on whether the root row exists before
     * the write — existence is determined inside the transaction, so the decision
     * is atomic with the write. Mirrors [SyncableRepository.upsert] exactly.
     *
     * **Retries are intentionally re-applied, not deduplicated.** [clientOpId] is stored on the
     * root row for audit/correlation only — it is never checked against a prior write to short-
     * circuit this method. A retried outbox operation (the client resending an already-committed
     * write after a dropped ack) therefore burns a fresh revision and re-publishes a duplicate
     * [SyncEvent.Updated] here, exactly as if it were a genuinely new write. This is safe by
     * design: every write is a full-payload overwrite (last-write-wins), so re-applying identical
     * content converges to the same row — the cost is one burned revision and one duplicate event
     * per retry, not a correctness gap. The client's own echo-shield (`OutboxInFlightQuery`)
     * suppresses the local UI flicker a duplicate event would otherwise cause; it is a client-side
     * concern, not something this method needs to protect against.
     */
    open suspend fun upsert(
        value: T,
        clientOpId: String? = null,
        userId: String? = null,
    ): AppResult<T> {
        if (userScoped) {
            requireNotNull(userId) { "user-scoped write on '$domainName' requires a userId" }
        }
        // Read the suppression marker in the outer suspend context, before the transaction:
        // the revision still bumps and the row still commits, but a suppressed write skips
        // the live-tail publish (see [FirehoseSuppressed]).
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            val idStr = idAsString(value.id)

            val existed = substrate.existsById(idStr)

            writePayload(value, rev, now, clientOpId, userId, existed)

            val saved =
                readPayload(idStr)
                    ?: error("readPayload returned null immediately after writePayload for $idStr")

            val event =
                if (existed) {
                    SyncEvent.Updated(
                        id = idStr,
                        revision = rev,
                        occurredAt = now,
                        clientOpId = clientOpId,
                        payload = saved,
                    )
                } else {
                    SyncEvent.Created(
                        id = idStr,
                        revision = rev,
                        occurredAt = now,
                        clientOpId = clientOpId,
                        payload = saved,
                    )
                }
            if (!suppressed) {
                deferEmit(event, userId)
            } else {
                log.debug { "change suppressed (firehose): domain=$domainName id=$idStr" }
            }

            AppResult.Success(saved)
        }
    }

    /**
     * Insert-or-update [value] inside an **already-open** SQLDelight transaction — the
     * batched-write counterpart to [upsert].
     *
     * [upsert] opens its own [suspendTransaction] per call, so writing N rows costs N transactions.
     * A bulk caller that has already opened one [suspendTransaction] for a whole chunk calls this per
     * row from inside it, collapsing the chunk to a single commit. The body is identical to [upsert]'s
     * transaction body — [nextRevision], [substrate.existsById][SyncableSubstrateQueries.existsById],
     * [writePayload], read-back, and the post-commit emit via [deferEmit] — minus the wrapper.
     *
     * [suppressed] is passed in because the synchronous transaction body cannot read the suspend-only
     * coroutine context: the caller reads `currentCoroutineContext()[FirehoseSuppressed.Key]` ONCE
     * before its chunk loop and threads the result here, exactly the [FirehoseSuppressed] gate
     * [upsert] applies. A suppressed write still bumps the revision and commits the row; it only
     * skips registering the live-tail emit.
     *
     * Returns the saved aggregate (the read-back), so the bulk caller can collect persisted payloads
     * without a second read.
     *
     * **Must run inside the caller's open transaction** — typically each row inside its own nested
     * `transactionWithResult { }` savepoint so a single malformed row rolls back in isolation while
     * the rest of the chunk commits (SQLDelight transfers a committed nested txn's afterCommit hooks
     * to the enclosing one and discards a rolled-back one's, so a failed row emits nothing).
     */
    protected fun TransactionWithReturn<*>.upsertInOpenTransaction(
        value: T,
        suppressed: Boolean,
        clientOpId: String? = null,
        userId: String? = null,
    ): T {
        if (userScoped) {
            requireNotNull(userId) { "user-scoped write on '$domainName' requires a userId" }
        }
        val rev = nextRevision()
        val now = clock.now().toEpochMilliseconds()
        val idStr = idAsString(value.id)

        val existed = substrate.existsById(idStr)

        writePayload(value, rev, now, clientOpId, userId, existed)

        val saved =
            readPayload(idStr)
                ?: error("readPayload returned null immediately after writePayload for $idStr")

        val event =
            if (existed) {
                SyncEvent.Updated(
                    id = idStr,
                    revision = rev,
                    occurredAt = now,
                    clientOpId = clientOpId,
                    payload = saved,
                )
            } else {
                SyncEvent.Created(
                    id = idStr,
                    revision = rev,
                    occurredAt = now,
                    clientOpId = clientOpId,
                    payload = saved,
                )
            }
        if (!suppressed) {
            deferEmit(event, userId)
        } else {
            log.debug { "change suppressed (firehose): domain=$domainName id=$idStr" }
        }
        return saved
    }

    /**
     * Registers the live-tail emit for [event] to fire **after** this SQLDelight
     * transaction commits, in publish order, via SQLDelight's own
     * [TransactionCallbacks.afterCommit].
     *
     * `afterCommit` semantics that make this the firehose's commit-deferral mechanism:
     *  - the hook runs only on the **commit** path (a rolled-back / failed
     *    transaction runs `afterRollback` and discards the commit hooks — no phantom
     *    emit), and only after SQLDelight has issued the JDBC `COMMIT`, so the
     *    firehose's delivery-time `BookAccessPolicy.canAccess` read never races an
     *    uncommitted row;
     *  - hooks run in **insertion (FIFO) order**, which is publish order, which is
     *    revision order — so multiple writes in one transaction emit in revision order;
     *  - in a **nested** transaction, SQLDelight transfers a child's commit hooks to
     *    the enclosing transaction, so they flush once at the outermost commit, still
     *    in insertion order — one flush per outermost transaction.
     *
     * The emit itself goes through [ChangeBus.emit] (immediate, no further deferral),
     * because by the time the hook fires we are already past commit. The
     * [FirehoseSuppressed] gate is applied by the caller (the `!suppressed` check in
     * [upsert] / [softDelete]) before this is ever reached, so a suppressed write
     * registers no hook and emits nothing.
     */
    private fun TransactionCallbacks.deferEmit(
        event: SyncEvent<T>,
        userId: String?,
    ) {
        afterCommit { bus.emit(repo = this@SqlSyncableRepository, event = event, userId = userId) }
    }

    /**
     * Subclass-facing entry point to the after-commit emit, for bulk operations that
     * write several rows in one [suspendTransaction] and must publish one event per row.
     *
     * Identical deferral semantics to [deferEmit] (the same `afterCommit` hook the base's
     * own [upsert] / [softDelete] use): the event fires after this SQLDelight transaction
     * commits, in publish order. Bulk soft-deletes (e.g.
     * [BookTagRepository.softDeleteAllForTag]) call this once per tombstoned row from
     * inside the open transaction so every per-row [SyncEvent.Deleted] lands on the live
     * tail post-commit, matching the Exposed base's per-row outbox behaviour.
     */
    protected fun TransactionCallbacks.emitAfterCommit(
        event: SyncEvent<T>,
        userId: String? = null,
    ) {
        deferEmit(event, userId)
    }

    /**
     * Serializes a domain id to its raw string representation for substrate queries
     * and [SyncEvent] entity ids. Defaults to `id.toString()`, correct for `String`
     * ids (e.g., Tags). **MUST be overridden for `@JvmInline value class` ids** —
     * see [SyncableRepository.idAsString] for the rationale.
     */
    protected open fun idAsString(id: ID): String = id.toString()

    /**
     * Marks the row as deleted by setting `deleted_at`. Bumps revision and publishes
     * [SyncEvent.Deleted]. Returns [AppResult.Failure] (`SyncError.NotFound`) if no
     * row exists with the given id. Mirrors [SyncableRepository.softDelete] exactly.
     */
    open suspend fun softDelete(
        id: ID,
        clientOpId: String? = null,
        userId: String? = null,
    ): AppResult<Unit> {
        if (userScoped) {
            requireNotNull(userId) { "user-scoped write on '$domainName' requires a userId" }
        }
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        return suspendTransaction(db) {
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            val idStr = idAsString(id)
            val rowsAffected =
                substrate.softDeleteById(
                    id = idStr,
                    revision = rev,
                    updatedAt = now,
                    deletedAt = now,
                    clientOpId = clientOpId,
                )
            if (rowsAffected == 0L) {
                AppResult.Failure(
                    SyncError.NotFound(
                        domain = domainName,
                        entityId = idStr,
                    ),
                )
            } else {
                if (!suppressed) {
                    deferEmit(
                        event =
                            SyncEvent.Deleted(
                                id = idStr,
                                revision = rev,
                                occurredAt = now,
                                clientOpId = clientOpId,
                            ),
                        userId = userId,
                    )
                } else {
                    log.debug { "change suppressed (firehose): domain=$domainName id=$idStr" }
                }
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Returns up to [limit] aggregates whose root row has `revision > cursor`,
     * ordered by revision ascending, each hydrated via [readPayloads] (child rows
     * included). Soft-deleted aggregates are returned so clients can apply
     * tombstones — in minimized form: content fields are blanked by
     * [minimizeTombstone] before they leave this method, so a tombstone crosses the
     * wire with identity + sync-discipline fields only. [Page.hasMore] is true when
     * the result hit the limit.
     *
     * `nextCursor` advances using the queried revision (the last id/rev pair's
     * revision) rather than `items.last().revisionOf()` — a hard delete between the
     * id-query and the payload-read could null a row, and the queried revision is
     * the canonical cursor advance regardless. Mirrors [SyncableRepository.pullSince].
     *
     * For a user-scoped domain ([userScoped] `= true`) the id query routes through
     * [SyncableSubstrateQueries.selectIdsAboveRevisionForUser] with a required non-null
     * [userId], so the page covers only that user's rows — exactly the `AND user_id = ?`
     * the Exposed base applies. Global domains ignore [userId].
     *
     * When [extraWhere] is non-null the page is **access-filtered**: the id query splices the
     * access subquery as `id IN (…)` and runs engine-neutrally over the [driver] (see
     * [selectIdRevAccessFiltered]). This is the first-class path every access-gated aggregate
     * (books, activities, collections, collection grants/books, library folders, admin roster)
     * inherits — no per-domain override. An ungated domain always passes a null fragment and
     * takes the substrate path unchanged.
     */
    override suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment?,
    ): Page<T> =
        suspendTransaction(db) {
            val idsWithRev: List<IdRev> =
                when {
                    extraWhere != null -> {
                        accessDriver().selectIdRevAccessFiltered(
                            table = rootTableName,
                            predicate = SqlFragment(sql = "revision > ?", args = listOf(cursor)),
                            extraWhere = extraWhere,
                            ascendingByRevision = true,
                            limit = limit,
                            // Catch-up must deliver deletions: a tombstone passes the access gate so a
                            // member who missed the live delete can still learn to remove the row and
                            // converge. Mirrors the firehose (Deleted events are tombstone-ungated).
                            includeTombstones = true,
                        )
                    }

                    userScoped -> {
                        val scopedUserId =
                            requireNotNull(userId) { "user-scoped pullSince on '$domainName' requires a userId" }
                        substrate.selectIdsAboveRevisionForUser(scopedUserId, cursor, limit.toLong())
                    }

                    else -> {
                        substrate.selectIdsAboveRevision(cursor, limit.toLong())
                    }
                }
            val items = readPayloads(idsWithRev.map { it.id }).map(::minimizedIfTombstoned)
            Page(
                items = items,
                nextCursor = idsWithRev.lastOrNull()?.revision,
                hasMore = idsWithRev.size == limit,
            )
        }

    /**
     * Access-filtered targeted read: the aggregates whose [matchColumn] is in [matchValues] and
     * which the caller can still see — the read half of the scoped `AccessChanged` delta.
     *
     * Where [pullSince] walks the revision axis, this walks an explicit id set. It is **always**
     * access-filtered (via the same [selectIdRevAccessFiltered] path as the filtered [pullSince]),
     * so a returned row is one the caller is entitled to; an id the client asked about but that does
     * **not** come back is either gone or no longer accessible, and the client tombstones it. The
     * result is therefore ⊆ what an unbounded `since = 0` catch-up would return — no new surface.
     *
     * [matchColumn] is a closed, code-controlled column name (`"id"` for the books/collections
     * targeted fetch, `"collection_id"` for the collection-books-by-collection fetch, `"book_id"` for
     * the activities-by-book fetch), never user input — the same trust level as [rootTableName]. The
     * sync route allowlists `book_id` to domains that actually have that column. [matchValues] are
     * bound positionally.
     *
     * Tombstones are included ([selectIdRevAccessFiltered]'s `includeTombstones = true`), matching
     * the filtered [pullSince] catch-up contract: a deletion the client missed is delivered so it
     * can converge. The page is un-paged — the caller (the sync route) caps the request size, so
     * `nextCursor` is null and `hasMore` is false.
     *
     * When [extraWhere] is null but a [driver] is wired (an all-seeing role on a gated domain) the
     * access clause is dropped and every matched row returns.
     *
     * A domain with **no wired [driver]** — every userScoped/global aggregate — cannot serve the
     * access-filtered read this performs, so it degrades to an **empty page** rather than failing
     * loud. Those domains are served via the `?since=` catch-up, and the client gates its
     * reconcile-on-drain targeted fetch to access-filtered handlers so it never asks them; the empty
     * page is the defense-in-depth backstop that keeps a stray `?ids=` request from 500ing. This is
     * a valid "nothing to reconcile here" answer — convergence still rides `?since=`/newer-wins.
     */
    override suspend fun pullByIds(
        userId: String?,
        matchColumn: String,
        matchValues: List<String>,
        extraWhere: SqlFragment?,
    ): Page<T> {
        if (matchValues.isEmpty()) return Page(items = emptyList(), nextCursor = null, hasMore = false)
        // No driver → this domain has no access-filtered read capability (see KDoc): answer empty
        // rather than throw, so a valid authenticated sync GET never 500s.
        val accessFilterDriver = driver ?: return Page(items = emptyList(), nextCursor = null, hasMore = false)
        return suspendTransaction(db) {
            val placeholders = matchValues.joinToString(separator = ", ") { "?" }
            val idsWithRev =
                accessFilterDriver.selectIdRevAccessFiltered(
                    table = rootTableName,
                    predicate = SqlFragment(sql = "$matchColumn IN ($placeholders)", args = matchValues),
                    extraWhere = extraWhere,
                    ascendingByRevision = false,
                    limit = null,
                    includeTombstones = true,
                )
            val items = readPayloads(idsWithRev.map { it.id }).map(::minimizedIfTombstoned)
            Page(items = items, nextCursor = null, hasMore = false)
        }
    }

    /**
     * Returns a [DomainDigest] over the LIVE rows with `revision <= cursor` — tombstones are
     * excluded (both the substrate slice and the access-filtered slice drop `deleted_at IS NOT
     * NULL`). Used by clients to detect drift cheaply. The exclusion is symmetric with the client's
     * tombstone-excluding `digestRows`, so a member who tombstoned a row locally (a delivered
     * deletion or an `AccessGate` prune) still converges — the deleted row leaves both digests at
     * once, instead of lingering in the client's forever (F1). Deletions still reach clients: the
     * live firehose and the (now tombstone-ungated) filtered [pullSince] both deliver them.
     *
     * The `(id, revision)` slice — from the substrate (global or [userScoped]) or, when
     * [extraWhere] is non-null, from the access-filtered driver read — is sorted by id and
     * folded into the digest by [accessFilteredDigest], the single implementation of the
     * permanent-wire-contract SHA-256 algorithm (lexicographic-by-id, `<id>|<revision>` joined
     * with `\n` and a trailing `\n`, `"sha256:<lowercase-hex>"`; empty → `count = 0, hash = ""`).
     *
     * For a user-scoped domain ([userScoped] `= true`) the slice routes through
     * [SyncableSubstrateQueries.selectIdRevAtMostForUser] with a required non-null
     * [userId], so the digest covers only that user's rows. Global domains ignore
     * [userId].
     *
     * When [extraWhere] is non-null the slice is **access-filtered** engine-neutrally over the
     * [driver], the first-class counterpart to the filtered [pullSince] path — every access-gated
     * aggregate inherits it, no per-domain override.
     */
    override suspend fun digest(
        userId: String?,
        cursor: Long,
        extraWhere: SqlFragment?,
    ): DomainDigest {
        val rows =
            suspendTransaction(db) {
                when {
                    extraWhere != null -> {
                        accessDriver().selectIdRevAccessFiltered(
                            table = rootTableName,
                            predicate = SqlFragment(sql = "revision <= ?", args = listOf(cursor)),
                            extraWhere = extraWhere,
                            ascendingByRevision = false,
                            limit = null,
                            // The digest counts only LIVE accessible rows — the access subquery already
                            // excludes tombstones (`deleted_at IS NULL`), symmetric with the client's
                            // tombstone-excluding digest. Delivering tombstones here would break parity.
                            includeTombstones = false,
                        )
                    }

                    userScoped -> {
                        val scopedUserId =
                            requireNotNull(userId) { "user-scoped digest on '$domainName' requires a userId" }
                        substrate.selectIdRevAtMostForUser(scopedUserId, cursor)
                    }

                    else -> {
                        substrate.selectIdRevAtMost(cursor)
                    }
                }
            }.sortedBy { it.id }
        return accessFilteredDigest(cursor, rows)
    }

    /**
     * Self-register with [SyncRegistry], keyed by [domainName] — identical to the
     * Exposed base's `init { registry.register(this) }`.
     *
     * Both bases now implement [SyncableRepo], and [SyncRegistry.register] / [ChangeBus]
     * are widened to that interface, so a SQLDelight repository registers and publishes
     * through the same plumbing as an Exposed one. Called from the `init` block; the
     * registry reads only [domainName] (a constructor val), so registration is safe
     * before the subclass's properties are initialized.
     */
    private fun register() {
        registry.register(this)
    }
}
