package com.calypsan.listenup.server.sync

/**
 * A self-contained, parameterised raw-SQL fragment: a statement (or subquery) and the
 * positionally-bound arguments its `?` placeholders consume, in order.
 *
 * [args] are **raw bind values** (`String`, `Long`, `Int`, …) with no column-type wrapper: the
 * SQLDelight [app.cash.sqldelight.db.SqlDriver] binds each by index, in list order.
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
