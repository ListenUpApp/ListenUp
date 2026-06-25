package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.QueryResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Pins the bulk-write throughput fix: the production JVM [DriverFactory] must open its SQLite
 * connections with `PRAGMA synchronous = NORMAL` (= 1), not the SQLite default `FULL` (= 2).
 *
 * Library persistence and ABS-import progress commit one small transaction PER book/row, so under
 * the default `FULL` each of those many commits pays an `fsync`, which dominated the wall-clock of
 * those bulk flows. `NORMAL` is the SQLite-recommended WAL companion: an `fsync` is taken only at a
 * WAL checkpoint, so the per-commit cost collapses while commits stay crash-durable and the DB is
 * never corrupted.
 *
 * The setting is asserted via a real `PRAGMA synchronous` read over the driver — [JdbcSqliteDriver]
 * applies the value as a JDBC connection *property* (not a transient one-shot PRAGMA), so the read
 * reflects what every production connection actually gets.
 */
class DriverFactorySynchronousPragmaTest :
    FunSpec({
        test("production driver opens connections with PRAGMA synchronous = NORMAL (1)") {
            val tmp =
                Files
                    .createTempFile("listenup-synchronous-pragma-", ".db")
                    .also { it.toFile().deleteOnExit() }
            val driver = DriverFactory().createDriver(tmp.toAbsolutePath().toString())

            val synchronous =
                driver
                    .executeQuery(
                        identifier = null,
                        sql = "PRAGMA synchronous",
                        mapper = { cursor ->
                            cursor.next()
                            QueryResult.Value(cursor.getLong(0)!!)
                        },
                        parameters = 0,
                    ).value

            // 0 = OFF, 1 = NORMAL, 2 = FULL, 3 = EXTRA. NORMAL is the durable-but-fast WAL companion.
            synchronous shouldBe 1L

            driver.close()
        }
    })
