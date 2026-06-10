package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Abstract base for syncable-domain repositories. Subclasses provide:
 *  - The Exposed table (a [SyncableTable] subtype) for the aggregate root
 *  - `readPayload(idStr)` — aggregate read (row + children) by id
 *  - `writePayload(value, rev, now, clientOpId, userId, existed)` — aggregate write inside the open transaction
 *  - The `T.id` projection
 *  - The `T.revisionOf()` projection
 *
 * The base provides `upsert`, `softDelete`, `pullSince`, `digest` operating
 * on the global revision counter and publishing to [ChangeBus] on every write.
 * It owns transaction orchestration, revision bumping, timestamping, and the
 * existed/created/updated discrimination — subclasses focus purely on the
 * aggregate's read/write shape. Single-table domains write one row;
 * aggregate domains (e.g., book + contributors + chapters) write the full
 * aggregate inside the same open transaction.
 *
 * Self-registers with the injected [SyncRegistry] in its `init` block — Koin
 * must use `createdAtStart = true` so registration happens at application
 * bootstrap.
 */
abstract class SyncableRepository<T : Any, ID : Any>(
    protected val db: Database,
    protected val table: SyncableTable,
    protected val bus: ChangeBus,
    registry: SyncRegistry,
    val domainName: String,
    protected val clock: Clock = Clock.System,
) {
    init {
        registry.register(this)
    }

    /**
     * `true` for a per-user domain whose [table] is a [UserScopedSyncableTable]:
     * every write records the owning user and every read/digest filters by it.
     * Default `false` is a global domain (books, contributors, series, tags) —
     * the `userId` argument is ignored and behaviour is unchanged.
     */
    protected open val userScoped: Boolean = false

    /**
     * Read the full aggregate (root row + children) for [idStr], or null if absent.
     *
     * **Called only from inside an active Exposed transaction** (opened by base
     * methods like `upsert`, `pullSince`, `softDelete`). Implementations issue
     * DSL queries directly — those bind to the open transaction.
     */
    protected abstract suspend fun readPayload(idStr: String): T?

    /**
     * Batch variant of [readPayload]: hydrate many aggregates by id, returning them
     * in the same order as [idStrs] and skipping ids whose root row is absent.
     *
     * The default delegates per-id. Domains whose [readPayload] issues several child
     * queries override this to batch those reads (see `BookRepository`). Called inside
     * the transaction opened by [pullSince].
     */
    protected open suspend fun readPayloads(idStrs: List<String>): List<T> =
        idStrs.mapNotNull { readPayload(it) }

    /**
     * Write the full aggregate (root row + children) inside the open transaction.
     *
     * The base class has already determined [rev] (next revision) and [now]
     * (timestamp), and resolved whether the root row [existed]. The
     * implementation MUST:
     *   - INSERT the root row if `!existed`, with `rev`, `createdAt = now`,
     *     `updatedAt = now`, `deletedAt = null`
     *   - UPDATE the root row if `existed`, with `rev`, `updatedAt = now`,
     *     `deletedAt = null`
     *   - Replace child rows wholesale (delete-then-insert), if any
     *   - Set `clientOpId` on the root row
     *
     * Atomicity is the contract: the base opens one `suspendTransaction`; this
     * method writes everything inside it.
     *
     * [userId] is the owning user for a per-user domain (`userScoped = true`),
     * to be written into the `user_id` column on insert; it is `null` for
     * global domains, which ignore it.
     */
    protected abstract suspend fun writePayload(
        value: T,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    )

    /**
     * kotlinx.serialization serializer for the concrete DTO type [T].
     * Provided by each subclass so the route handler can encode [Page] responses
     * without losing type information through the type-erased registry.
     */
    abstract val elementSerializer: KSerializer<T>

    /**
     * Encodes a [Page] of [T] to a JSON string using [contractJson] and the
     * concrete [elementSerializer]. Called by the catch-up route to work around
     * the type-erasure of the registry (`SyncableRepository<Any, Any>` cast).
     */
    fun encodePageAsJson(page: Page<T>): String {
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
     * Encodes a [SyncEvent] to a JSON string using [contractJson] and the
     * concrete [elementSerializer]. Called by the SSE firehose to serialise
     * the event payload without losing type information through the
     * type-erased registry (`SyncableRepository<Any, Any>` cast).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun encodeSyncEventAsJson(event: SyncEvent<*>): String =
        contractJson.encodeToString(SyncEvent.serializer(elementSerializer), event as SyncEvent<T>)

    protected abstract val T.id: ID

    /** Subclass-provided projection of the DTO's revision. */
    protected abstract fun T.revisionOf(): Long

    /**
     * Insert or update [value], bumping the global revision counter and publishing
     * a [SyncEvent.Created] or [SyncEvent.Updated] to [ChangeBus].
     *
     * Created/Updated discrimination is based on whether the root row exists before
     * the write — existence is determined inside the transaction, so the decision
     * is atomic with the write. The full aggregate (root + children) is written
     * inside the same transaction via [writePayload].
     *
     * @param clientOpId originating client operation id for SSE echo matching; null
     *   for server-initiated writes (scanner, admin, etc.).
     * @param userId owning user for a per-user domain (`userScoped = true`);
     *   threaded into [writePayload] and the published [BusEvent]. Global
     *   domains omit it.
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

            val existed =
                table
                    .selectAll()
                    .where { idColumn() eq idStr }
                    .empty()
                    .not()

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
                bus.publish(repo = this@SyncableRepository, event = event, userId = userId)
            }

            AppResult.Success(saved)
        }
    }

    /**
     * Serializes a domain id to its raw string representation for WHERE clauses,
     * UPDATE statements, and [SyncEvent] entity ids. Defaults to `id.toString()`,
     * which is correct for `String` ids (e.g., Tags).
     *
     * **MUST be overridden for `@JvmInline value class` ids.** Kotlin's default
     * `toString()` on a value class returns `"WrapperName(value=foo)"`, which would
     * corrupt every column the id is written to (primary key, WHERE clauses,
     * `SyncEvent.id`). Override to return the raw underlying string — e.g.,
     * `override fun idAsString(id: BookId) = id.value`.
     *
     * The Konsist rule `IdAsStringRequiredForValueClassIdsRule` enforces this
     * override at build time.
     */
    protected open fun idAsString(id: ID): String = id.toString()

    /**
     * Exposes the table's primary-key column for use in WHERE clauses.
     * Default assumes a `text("id")` column — the canonical syncable-table
     * convention. Domains using a non-`id` PK column override this.
     */
    protected open fun idColumn(): Column<String> {
        @Suppress("UNCHECKED_CAST")
        return table.columns.firstOrNull { it.name == "id" } as? Column<String>
            ?: error("Domain table ${table.tableName} has no 'id' column; override idColumn().")
    }

    /**
     * Marks the row as deleted by setting `deleted_at`. Bumps revision and
     * publishes [SyncEvent.Deleted]. Returns [AppResult.Failure] if no row exists
     * with the given id.
     *
     * @param clientOpId originating client operation id for SSE echo matching; null
     *   for server-initiated deletes.
     * @param userId owning user for a per-user domain (`userScoped = true`);
     *   threaded into the published [BusEvent]. Global domains omit it.
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
                table.update({ idColumn() eq idStr }) { stmt ->
                    stmt[table.revision] = rev
                    stmt[table.updatedAt] = now
                    stmt[table.deletedAt] = now
                    stmt[table.clientOpId] = clientOpId
                }
            if (rowsAffected == 0) {
                AppResult.Failure(
                    SyncError.NotFound(
                        domain = domainName,
                        entityId = idStr,
                    ),
                )
            } else {
                if (!suppressed) {
                    bus.publish(
                        repo = this@SyncableRepository,
                        event =
                            SyncEvent.Deleted(
                                id = idStr,
                                revision = rev,
                                occurredAt = now,
                                clientOpId = clientOpId,
                            ),
                        userId = userId,
                    )
                }
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Resolves the WHERE-clause user-filter column for a per-user domain.
     *
     * For `userScoped = true` it returns `(table as UserScopedSyncableTable).userId`
     * paired with the caller-supplied [userId], which must be non-null — a
     * per-user read with no authenticated user is a programming error. For a
     * global domain it returns `null`: no extra predicate is applied and
     * [userId] is ignored.
     */
    private fun userFilter(userId: String?): Pair<Column<String>, String>? =
        if (userScoped) {
            val scopedTable =
                (table as? UserScopedSyncableTable)
                    ?: error("userScoped repo '$domainName' must use a UserScopedSyncableTable")
            val column = scopedTable.userId
            column to (userId ?: error("user-scoped read on '$domainName' requires a userId"))
        } else {
            null
        }

    /**
     * Combines [this] predicate with the per-user equality predicate when this repo is
     * user-scoped, leaving it unchanged for global domains. Keeps [pullSince] and
     * [digest] WHERE-clause composition in one place.
     */
    private fun Op<Boolean>.withUserFilter(userId: String?): Op<Boolean> {
        val filter = userFilter(userId) ?: return this
        return this and (filter.first eq filter.second)
    }

    /**
     * Returns up to [limit] aggregates whose root row has `revision > cursor`,
     * ordered by revision ascending. Each aggregate is hydrated via [readPayload]
     * so child rows are included. Soft-deleted aggregates are returned so clients
     * can apply tombstones. [Page.hasMore] is true when the result set hit the
     * limit, signalling more pages remain.
     *
     * For a per-user domain (`userScoped = true`) the result is additionally
     * scoped to [userId]'s rows; global domains ignore [userId].
     *
     * `nextCursor` advances using the queried revision rather than
     * `items.last().revisionOf()` — a hard delete between the id-query and the
     * payload-read could theoretically null a row, and the queried revision is
     * the canonical cursor advance regardless.
     */
    open suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment? = null,
    ): Page<T> =
        suspendTransaction(db) {
            val idsWithRev =
                if (extraWhere == null) {
                    table
                        .selectAll()
                        .where { (table.revision greater cursor).withUserFilter(userId) }
                        .orderBy(table.revision, SortOrder.ASC)
                        .limit(limit)
                        .map { it[idColumn()] to it[table.revision] }
                } else {
                    selectIdsWithRevRaw(
                        revisionPredicate = "${table.revision.name} > ?",
                        revisionArg = cursor,
                        extraWhere = extraWhere,
                        orderAndLimit = "ORDER BY ${table.revision.name} ASC LIMIT ?",
                        trailingArgs = listOf(IntegerColumnType() to limit),
                    )
                }

            val items = readPayloads(idsWithRev.map { (idStr, _) -> idStr })

            Page(
                items = items,
                nextCursor = idsWithRev.lastOrNull()?.second,
                hasMore = idsWithRev.size == limit,
            )
        }

    /**
     * Returns a [DomainDigest] over all rows with `revision <= cursor`, soft-deleted rows
     * included. Used by clients to detect drift cheaply — a `(count, hash)` mismatch signals
     * the client should re-pull from `?since=0`.
     *
     * For a per-user domain (`userScoped = true`) the digest covers only [userId]'s
     * rows; global domains ignore [userId].
     *
     * Algorithm: sort `(id, revision)` pairs lexicographically by id, concatenate as
     * `<id>|<revision>\n` per row, SHA-256 the bytes, format as `"sha256:<lowercase-hex>"`.
     * Empty domain → `count = 0`, `hash = ""`. This is a permanent wire contract — clients
     * compute identically over their local rows.
     */
    suspend fun digest(
        userId: String?,
        cursor: Long,
        extraWhere: SqlFragment? = null,
    ): DomainDigest =
        suspendTransaction(db) {
            val rows =
                if (extraWhere == null) {
                    table
                        .selectAll()
                        .where { (table.revision lessEq cursor).withUserFilter(userId) }
                        .map { row -> row[idColumn()] to row[table.revision] }
                        .sortedBy { it.first }
                } else {
                    selectIdsWithRevRaw(
                        revisionPredicate = "${table.revision.name} <= ?",
                        revisionArg = cursor,
                        extraWhere = extraWhere,
                        orderAndLimit = "",
                        trailingArgs = emptyList(),
                    ).sortedBy { it.first }
                }
            if (rows.isEmpty()) {
                DomainDigest(cursor = cursor, count = 0, hash = "")
            } else {
                val md = MessageDigest.getInstance("SHA-256")
                val joined =
                    rows.joinToString(separator = "\n") { (id, rev) -> "$id|$rev" } + "\n"
                val hex =
                    md
                        .digest(joined.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                DomainDigest(cursor = cursor, count = rows.size, hash = "sha256:$hex")
            }
        }

    /**
     * Raw-SQL `(id, revision)` query for the access-filtered ([extraWhere] non-null) path.
     *
     * Splices the access subquery as `<idColumn> IN (<extraWhere.sql>)` into a `WHERE`
     * that also carries the revision predicate. The DSL path stays the canonical
     * implementation for every domain that passes no fragment; this raw path exists
     * only because [SqlFragment] carries a complete parameterised subquery that
     * Exposed's DSL `where { }` cannot splice without reconstructing the placeholder
     * binding by hand.
     *
     * **Argument order is load-bearing** and matches the `?` order in the assembled
     * SQL exactly: the [revisionArg] (the `?` in [revisionPredicate]) first, then the
     * [extraWhere] args in their existing order, then any [trailingArgs] (the LIMIT
     * `?`). A wrong order would silently bind a value to the wrong placeholder.
     *
     * **Called only from inside an active Exposed transaction** (opened by [pullSince]
     * / [digest]).
     */
    private fun selectIdsWithRevRaw(
        revisionPredicate: String,
        revisionArg: Long,
        extraWhere: SqlFragment,
        orderAndLimit: String,
        trailingArgs: List<Pair<IColumnType<*>, Any>>,
    ): List<Pair<String, Long>> {
        val idCol = idColumn().name
        val sql =
            buildString {
                append("SELECT $idCol, ${table.revision.name} FROM ${table.tableName} ")
                append("WHERE $revisionPredicate AND $idCol IN (${extraWhere.sql})")
                if (orderAndLimit.isNotEmpty()) append(" $orderAndLimit")
            }
        val args =
            buildList {
                add(LongColumnType() to revisionArg)
                addAll(extraWhere.args)
                addAll(trailingArgs)
            }
        val results = mutableListOf<Pair<String, Long>>()
        TransactionManager.current().exec(stmt = sql, args = args) { rs ->
            while (rs.next()) results += rs.getString(1) to rs.getLong(2)
        }
        return results
    }
}
