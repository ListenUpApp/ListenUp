package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class SyncMetaTest :
    FunSpec({
        test("nextRevision() increments monotonically from 1") {
            withSqlDatabase {
                runTest {
                    sql.transactionWithResult {
                        sql.substrateQueries.bumpRevision()
                        sql.substrateQueries.readRevision().executeAsOne()
                    } shouldBe 1L
                    sql.transactionWithResult {
                        sql.substrateQueries.bumpRevision()
                        sql.substrateQueries.readRevision().executeAsOne()
                    } shouldBe 2L
                    sql.transactionWithResult {
                        sql.substrateQueries.bumpRevision()
                        sql.substrateQueries.readRevision().executeAsOne()
                    } shouldBe 3L
                }
            }
        }

        test("nextRevision is strictly monotonic and never collides under 50 concurrent writers") {
            withSqlDatabase {
                // runBlocking (not runTest) — we need real Dispatchers.IO threads so
                // the 50 transactions race on actual SQLite connections. runTest's
                // virtual time would serialise them and miss the race.
                val revisions =
                    runBlocking {
                        coroutineScope {
                            (1..50)
                                .map {
                                    async(Dispatchers.IO) {
                                        sql.transactionWithResult {
                                            sql.substrateQueries.bumpRevision()
                                            sql.substrateQueries.readRevision().executeAsOne()
                                        }
                                    }
                                }.awaitAll()
                        }
                    }

                // Every value unique AND strictly covering 1..50.
                revisions.toSet().size shouldBe 50
                revisions.min() shouldBe 1L
                revisions.max() shouldBe 50L
            }
        }
    })
