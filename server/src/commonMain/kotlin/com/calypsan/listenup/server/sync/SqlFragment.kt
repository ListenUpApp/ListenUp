package com.calypsan.listenup.server.sync

/**
 * A self-contained, parameterised raw-SQL fragment: a statement (or subquery) and the
 * positionally-bound arguments its `?` placeholders consume, in order.
 *
 * [args] are **engine-neutral raw bind values** (`String`, `Long`, `Int`, … — never an
 * Exposed `IColumnType`), so a fragment can be executed by either engine: the SQLDelight
 * [app.cash.sqldelight.db.SqlDriver] binds each by index, and any other binder iterates the
 * same ordered list. The SQL text (subquery shape, `?` placeholders) is identical regardless
 * of who runs it — only the binding mechanism differs.
 *
 * Carrying the [args] alongside the [sql] keeps the two from drifting — a fragment is always
 * passed to its executor as a unit, so the placeholder count and the argument list can never
 * disagree at a call site. Unlike `SearchServiceImpl`'s private `FilterSql` (a bare
 * WHERE-clause tail), [SqlFragment] is a complete spliceable unit: a subquery the caller can
 * wrap (`SELECT … FROM ($sql)`) or run directly.
 */
data class SqlFragment(
    val sql: String,
    val args: List<Any?>,
)
