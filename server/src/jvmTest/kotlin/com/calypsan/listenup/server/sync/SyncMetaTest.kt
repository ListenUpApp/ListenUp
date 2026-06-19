package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class SyncMetaTest :
    FunSpec({
        test("nextRevision() increments monotonically from 1") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    suspendTransaction(db) { nextRevision() } shouldBe 1L
                    suspendTransaction(db) { nextRevision() } shouldBe 2L
                    suspendTransaction(db) { nextRevision() } shouldBe 3L
                }
            }
        }

        test("nextRevision is strictly monotonic and never collides under 50 concurrent writers") {
            withInMemoryDatabase {
                val db = this
                // runBlocking (not runTest) — we need real Dispatchers.IO threads so
                // the 50 transactions race on actual SQLite connections. runTest's
                // virtual time would serialise them and miss the race.
                val revisions =
                    runBlocking {
                        coroutineScope {
                            (1..50)
                                .map {
                                    async(Dispatchers.IO) {
                                        suspendTransaction(db) { nextRevision() }
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
