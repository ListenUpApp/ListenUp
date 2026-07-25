package com.calypsan.listenup.server.db.sqldelight

import app.cash.sqldelight.db.QueryResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

private const val HEX_RADIX = 16
private const val CONCURRENT_READS = 32

/**
 * Native runtime proof for [DriverFactory] on linuxX64: with the WAL reader pool (maxReaderConnections > 1)
 * a burst of concurrent reads on a multi-threaded dispatcher all succeed and return correct data without a
 * transient SQLITE_BUSY. Regression guard for the reader-pool sizing — if WAL or the busy-timeout were ever
 * dropped, or the pool returned to a single connection that BUSY'd under contention, this would surface it.
 */
class DriverFactoryConcurrentReadNativeTest :
    FunSpec({
        test("concurrent reads all succeed under the wal reader pool") {
            val dbName = "lu-driver-pool-test-${Random.nextInt(1, Int.MAX_VALUE).toString(HEX_RADIX)}.db"
            val driver = DriverFactory().createDriver(dbName)
            try {
                driver.execute(null, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)", 0)
                driver.execute(null, "INSERT INTO t(id, v) VALUES (1, 'hello')", 0)

                val reads =
                    (1..CONCURRENT_READS).map {
                        async(Dispatchers.Default) {
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT v FROM t WHERE id = 1",
                                    mapper = { cursor ->
                                        cursor.next()
                                        QueryResult.Value(cursor.getString(0))
                                    },
                                    parameters = 0,
                                ).value
                        }
                    }
                val results = reads.awaitAll()

                results.size shouldBe CONCURRENT_READS
                results.all { it == "hello" } shouldBe true
            } finally {
                driver.close()
                listOf(dbName, "$dbName-wal", "$dbName-shm").forEach {
                    SystemFileSystem.delete(Path(it), mustExist = false)
                }
            }
        }
    })
