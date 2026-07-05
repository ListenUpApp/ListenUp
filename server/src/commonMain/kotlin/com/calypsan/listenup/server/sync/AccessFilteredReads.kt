package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.server.io.hashBytesSha256

/**
 * Engine-neutral execution of the access-filtered `(id, revision)` reads that back the
 * `pullSince` / `digest` overrides of every access-scoped aggregate (books, library folders,
 * collections, collection grants, collection books).
 *
 * Each read splices a [SqlFragment] access subquery — the single visibility definition owned by
 * [com.calypsan.listenup.server.api.BookAccessPolicy] — as `id IN (<fragment.sql>)` into a
 * `WHERE` that also carries the revision predicate, then runs the whole thing over the SQLDelight
 * [SqlDriver]. This is the engine-neutral twin of the old Exposed `exec` path: same SQL text,
 * same placeholder order, raw bind values instead of Exposed-typed pairs.
 *
 * **Bind-arg ordering is security-relevant and load-bearing.** The placeholders bind, in order:
 *  1. every [predicate] arg (the row-selection clause) — the revision cursor for a `revision > ?`
 *     pull / `revision <= ?` digest slice, or the target ids for an `id IN (?, …)` /
 *     `collection_id IN (?, …)` targeted fetch — bound first, in [SqlFragment.args] order,
 *  2. then every [SqlFragment.args] value of the spliced access subquery, in its existing order,
 *  3. then the optional trailing `LIMIT ?` ([limit]).
 *
 * This is the EXACT order the prior Exposed `selectIdsWithRevRaw` used. A wrong order would
 * silently bind a value to the wrong placeholder — e.g. the cursor into the access subquery's
 * `owner_id = ?` — and a member could then see another user's or an inaccessible row. The single
 * implementation here means that ordering is defined once, not re-derived per aggregate.
 *
 * Bind indices are **0-based** to match the SQLDelight JDBC convention (the generated queries
 * bind from `parameterIndex = 0`; the driver adds 1 internally).
 *
 * **Called only from inside an active SQLDelight transaction** (the
 * `suspendTransaction(db) { … }` opened by each aggregate's filtered `pullSince` / `digest` /
 * `pullByIds`), so the raw query runs on the same connection as the surrounding read.
 *
 * @param table the aggregate's root table name (`"books"`, `"collections"`, …) — a closed,
 *   code-controlled string, never user input.
 * @param predicate the row-selection clause and its ordered args — `"revision > ?"` for a pull
 *   page, `"revision <= ?"` for a digest slice, `"id IN (?, …)"` / `"collection_id IN (?, …)"` for
 *   a targeted-by-ids fetch. The column names are code-controlled, never user input.
 * @param extraWhere the access subquery to splice as `id IN (<sql>)`, with its ordered raw args;
 *   `null` when the caller is unconstrained (an admin, or a targeted fetch by an all-seeing role) —
 *   the access clause is then omitted entirely and every matched row (tombstones included) returns.
 * @param ascendingByRevision when true, append `ORDER BY revision ASC` (the pull-page ordering;
 *   the digest slice and the targeted fetch are unordered — sorted by id in Kotlin if needed).
 * @param limit the page cap; when non-null a trailing `LIMIT ?` is appended and the value bound
 *   last (the pull path), null for the unbounded digest slice and targeted fetch.
 * @param includeTombstones when true, a tombstone (`deleted_at IS NOT NULL`) passes the access
 *   gate regardless of the subquery — the member needs to learn what to remove even for a row it
 *   can no longer "access" (a deleted row is never accessible: `accessibleBookIdsSql` requires
 *   `deleted_at IS NULL`). This mirrors the firehose's tombstone-ungated rule (`isBookEventHidden`
 *   lets every `SyncEvent.Deleted` through) so catch-up and the live tail agree, and it leaks no
 *   content — a tombstone carries only id/revision/deleted_at. Set true on the pull path (catch-up
 *   must deliver deletions), false on the digest path (the digest counts only LIVE accessible rows,
 *   symmetric with the tombstone-excluding client digest). Only meaningful when [extraWhere] is
 *   non-null; with no access clause, every matched row already returns.
 */
internal fun SqlDriver.selectIdRevAccessFiltered(
    table: String,
    predicate: SqlFragment,
    extraWhere: SqlFragment?,
    ascendingByRevision: Boolean,
    limit: Int?,
    includeTombstones: Boolean,
): List<IdRev> {
    val sql =
        buildString {
            append("SELECT id, revision FROM ")
            append(table)
            append(" WHERE ")
            append(predicate.sql)
            if (extraWhere != null) {
                append(" AND (id IN (")
                append(extraWhere.sql)
                append(")")
                if (includeTombstones) append(" OR deleted_at IS NOT NULL")
                append(")")
            }
            if (ascendingByRevision) append(" ORDER BY revision ASC")
            if (limit != null) append(" LIMIT ?")
        }
    // Placeholder count: predicate args + access-subquery args + (1 if limited).
    val parameterCount = predicate.args.size + (extraWhere?.args?.size ?: 0) + if (limit != null) 1 else 0
    return executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<IdRev>()
            while (cursor.next().value) {
                rows += IdRev(id = cursor.getString(0)!!, revision = cursor.getLong(1)!!)
            }
            QueryResult.Value(rows.toList())
        },
        parameters = parameterCount,
        binders = {
            // Bind in the exact placeholder order — the load-bearing invariant.
            var index = 0
            predicate.args.forEach { arg -> bindRaw(index++, arg) }
            extraWhere?.args?.forEach { arg -> bindRaw(index++, arg) }
            if (limit != null) bindLong(index, limit.toLong())
        },
    ).value
}

/**
 * Computes the access-filtered [DomainDigest] from the `(id, revision)` [rows] of a digest slice.
 *
 * Identical algorithm to [SqlSyncableRepository.digest] — a permanent wire contract: sort the
 * pairs lexicographically by id, join as `<id>|<revision>` per row with `\n` separators and a
 * trailing `\n`, SHA-256 the UTF-8 bytes, format as `"sha256:<lowercase-hex>"`. An empty slice is
 * `count = 0, hash = ""`. Factored here so every access-scoped aggregate's filtered `digest`
 * override computes the identical hash — a drift here would silently break drift-detection.
 *
 * [rows] must already be sorted by id (the raw read returns them unordered; callers sort first).
 */
internal fun accessFilteredDigest(
    cursor: Long,
    rows: List<IdRev>,
): DomainDigest {
    if (rows.isEmpty()) return DomainDigest(cursor = cursor, count = 0, hash = "")
    val joined = rows.joinToString(separator = "\n") { "${it.id}|${it.revision}" } + "\n"
    val hex = hashBytesSha256(joined.encodeToByteArray())
    return DomainDigest(cursor = cursor, count = rows.size, hash = "sha256:$hex")
}

/**
 * Binds a raw engine-neutral [SqlFragment]/filter arg to [index] (0-based), dispatching on its
 * runtime type to the matching typed [SqlPreparedStatement] binder.
 *
 * The access-control fragments bind only `String` ids; the search filter path additionally binds
 * `Long`/`Int` (durations, years) — those are the only types these raw reads ever carry. An
 * unsupported type is a programming error (a fragment carrying a value no `bind*` accepts), so it
 * fails loudly rather than silently mis-binding.
 */
internal fun SqlPreparedStatement.bindRaw(
    index: Int,
    value: Any?,
) {
    when (value) {
        null -> bindString(index, null)
        is String -> bindString(index, value)
        is Long -> bindLong(index, value)
        is Int -> bindLong(index, value.toLong())
        is Boolean -> bindBoolean(index, value)
        is Double -> bindDouble(index, value)
        else -> error("Unsupported bind arg type ${value::class.simpleName} at index $index")
    }
}
