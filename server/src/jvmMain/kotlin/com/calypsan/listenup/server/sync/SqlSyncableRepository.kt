package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
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
 *  - [elementSerializer] — the concrete DTO serializer
 *  - the `T.id`, `T.revisionOf()`, and (for value-class ids) [idAsString] projections
 *
 * Self-registration with [SyncRegistry] and live-tail publishing to [ChangeBus]
 * are deferred until the registry/bus type bounds are widened to admit this base
 * — see the class header note in `SqlSyncableRepository.kt` and the deferral
 * documented on [register] / [publishCreatedOrUpdated] / [publishDeleted].
 */
abstract class SqlSyncableRepository<T : Any, ID : Any>(
    protected val db: ListenUpDatabase,
    protected val bus: ChangeBus,
    protected val registry: SyncRegistry,
    val domainName: String,
    protected val clock: Clock = Clock.System,
) {
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
     * kotlinx.serialization serializer for the concrete DTO type [T].
     * Provided by each subclass so the route handler can encode [Page] responses
     * without losing type information through the type-erased registry.
     */
    abstract val elementSerializer: KSerializer<T>

    /**
     * Encodes a [Page] of [T] to a JSON string using [contractJson] and the
     * concrete [elementSerializer]. Verbatim from [SyncableRepository] — pure
     * serialization, no DB access.
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
     * Encodes a [SyncEvent] to a JSON string using [contractJson] and the concrete
     * [elementSerializer]. Verbatim from [SyncableRepository] — pure serialization,
     * no DB access.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun encodeSyncEventAsJson(event: SyncEvent<*>): String =
        contractJson.encodeToString(SyncEvent.serializer(elementSerializer), event as SyncEvent<T>)

    protected abstract val T.id: ID

    /** Subclass-provided projection of the DTO's revision. */
    protected abstract fun T.revisionOf(): Long

    /**
     * `true` for a per-user domain whose root table carries a `user_id` column:
     * every write records the owning user and every read/digest filters by it.
     * Default `false` is a global domain (Tags) — the `userId` argument is ignored.
     *
     * User-scoped read/write filtering is **deferred** in this base: no syncable
     * SQLDelight aggregate is user-scoped yet (Tag, the first conversion, is
     * global). When a user-scoped aggregate is converted, this base gains the
     * filtered substrate-query variants and the `userId`-equality predicate that
     * the Exposed base carries.
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
                publishCreatedOrUpdated(event, userId)
            }

            AppResult.Success(saved)
        }
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
                    publishDeleted(
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
     * Returns up to [limit] aggregates whose root row has `revision > cursor`,
     * ordered by revision ascending, each hydrated via [readPayloads] (child rows
     * included). Soft-deleted aggregates are returned so clients can apply
     * tombstones. [Page.hasMore] is true when the result hit the limit.
     *
     * `nextCursor` advances using the queried revision (the last id/rev pair's
     * revision) rather than `items.last().revisionOf()` — a hard delete between the
     * id-query and the payload-read could null a row, and the queried revision is
     * the canonical cursor advance regardless. Mirrors [SyncableRepository.pullSince].
     *
     * [extraWhere] (the access-filtered path) is **deferred** — see the parameter
     * note. It is accepted so route call sites stay source-compatible, but a
     * non-null fragment is not yet supported.
     */
    open suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment? = null,
    ): Page<T> {
        require(extraWhere == null) {
            "access-filtered pullSince (extraWhere) not supported in SqlSyncableRepository base; " +
                "override for access-filtered domains"
        }
        return suspendTransaction(db) {
            val idsWithRev = substrate.selectIdsAboveRevision(cursor, limit.toLong())
            val items = readPayloads(idsWithRev.map { it.id })
            Page(
                items = items,
                nextCursor = idsWithRev.lastOrNull()?.revision,
                hasMore = idsWithRev.size == limit,
            )
        }
    }

    /**
     * Returns a [DomainDigest] over all rows with `revision <= cursor`, soft-deleted
     * rows included. Used by clients to detect drift cheaply.
     *
     * Algorithm (identical to [SyncableRepository.digest], a permanent wire
     * contract): sort `(id, revision)` pairs lexicographically by id, join as
     * `<id>|<revision>` per row separated by `\n` with a trailing `\n`, SHA-256 the
     * UTF-8 bytes, format as `"sha256:<lowercase-hex>"`. Empty domain → `count = 0`,
     * `hash = ""`.
     *
     * [extraWhere] (the access-filtered path) is **deferred** — accepted for
     * source-compatibility, non-null not yet supported.
     */
    suspend fun digest(
        userId: String?,
        cursor: Long,
        extraWhere: SqlFragment? = null,
    ): DomainDigest {
        require(extraWhere == null) {
            "access-filtered digest (extraWhere) not supported in SqlSyncableRepository base; " +
                "override for access-filtered domains"
        }
        return suspendTransaction(db) {
            val rows =
                substrate
                    .selectIdRevAtMost(cursor)
                    .map { it.id to it.revision }
                    .sortedBy { it.first }
            if (rows.isEmpty()) {
                DomainDigest(cursor = cursor, count = 0, hash = "")
            } else {
                val md = MessageDigest.getInstance("SHA-256")
                val joined =
                    rows.joinToString(separator = "\n") { (id, rev) -> "$id|$rev" } + "\n"
                val hex =
                    md
                        .digest(joined.toByteArray(Charsets.UTF_8))
                        .toHexString()
                DomainDigest(cursor = cursor, count = rows.size, hash = "sha256:$hex")
            }
        }
    }

    /**
     * Self-register with [SyncRegistry].
     *
     * **Deferred.** [SyncRegistry.register] and [ChangeBus.publish] are statically
     * bound to the Exposed [SyncableRepository] type, so this SQLDelight base cannot
     * be registered or published through them without widening their type bounds —
     * a cross-cutting infra change that ripples into the firehose / catch-up routes
     * ([SyncRoutes]) and is explicitly out of scope for this additive scaffold.
     *
     * Wiring this base into the live registry/bus belongs with the first aggregate
     * conversion (Tag), where the registry/bus/firehose are migrated to admit both
     * bases together. Until then this is a no-op so the base compiles standalone;
     * all the orchestration that *would* publish ([publishCreatedOrUpdated],
     * [publishDeleted]) is in place and exercised, gated only on the bus binding.
     */
    private fun register() {
        // Deferred — see KDoc. Intentionally a no-op until the registry/bus type
        // bounds are widened to admit SqlSyncableRepository (Tag-conversion task).
    }

    /**
     * Publish a [SyncEvent.Created]/[SyncEvent.Updated] live-tail event.
     *
     * **Deferred** for the same reason as [register]: [ChangeBus.publish] is bound to
     * the Exposed base type. The call site in [upsert] is in place (under the
     * `!suppressed` guard) so the publish wires up with a one-line change once the
     * bus accepts this base.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun publishCreatedOrUpdated(
        event: SyncEvent<T>,
        userId: String?,
    ) {
        // Deferred — see KDoc.
    }

    /**
     * Publish a [SyncEvent.Deleted] tombstone.
     *
     * **Deferred** for the same reason as [register] / [publishCreatedOrUpdated].
     */
    @Suppress("UNUSED_PARAMETER")
    private fun publishDeleted(
        event: SyncEvent.Deleted,
        userId: String?,
    ) {
        // Deferred — see KDoc.
    }
}
