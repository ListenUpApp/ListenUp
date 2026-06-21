package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Runs an `(id, revision)` query over [this] driver, binding [args] in `?` order and
 * mapping every row to an [IdRev].
 *
 * Shared by the test-only [SqlSyncableRepository] fixtures ([UserScopedFixtureRepository],
 * `FixtureRepository`, `ThrowingRepository`) whose backing tables have no generated
 * SQLDelight queries — their schemas are test-only (no Flyway migration), so the substrate
 * runs raw SQL over the [SqlDriver] exactly the way [AccessFilteredReads] does for the
 * access-filtered production reads. Called only from inside the base's open
 * `suspendTransaction(db) { … }`, so the read shares that transaction's connection.
 */
internal fun SqlDriver.queryIdRev(
    sql: String,
    args: List<Any?>,
): List<IdRev> =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<IdRev>()
            while (cursor.next().value) {
                rows += IdRev(id = cursor.getString(0)!!, revision = cursor.getLong(1)!!)
            }
            QueryResult.Value(rows.toList())
        },
        parameters = args.size,
        binders = { args.forEachIndexed { index, arg -> bindRaw(index, arg) } },
    ).value
