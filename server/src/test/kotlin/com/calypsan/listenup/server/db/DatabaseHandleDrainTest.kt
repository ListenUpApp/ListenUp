package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.backup.backupTestFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DatabaseHandleDrainTest :
    FunSpec({
        test("awaitPoolDrained returns true once a returned connection is evicted") {
            backupTestFixture(withImages = false).use { fixture ->
                // Force the pool to open a physical connection, then return it (idle).
                transaction(fixture.handle.database) { exec("SELECT 1") { it.next() } }

                fixture.handle.suspendPool()
                fixture.handle.evictConnections()

                fixture.handle.awaitPoolDrained() shouldBe true
            }
        }

        test("awaitPoolDrained returns true within a short timeout when the pool is idle/empty") {
            backupTestFixture(withImages = false).use { fixture ->
                fixture.handle.suspendPool()
                fixture.handle.awaitPoolDrained(timeoutMs = 500L) shouldBe true
            }
        }
    })
