package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

        test("nextRevision() is strictly monotonic across concurrent calls") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val results = (1..50).map { suspendTransaction(db) { nextRevision() } }
                    results.toSet().size shouldBe 50
                    results shouldBe results.sorted()
                }
            }
        }
    })
