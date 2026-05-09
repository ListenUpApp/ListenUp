package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SyncMetaTest :
    FunSpec({
        test("nextRevision() increments monotonically from 1") {
            withInMemoryDatabase {
                runTest {
                    nextRevision(this@withInMemoryDatabase) shouldBe 1L
                    nextRevision(this@withInMemoryDatabase) shouldBe 2L
                    nextRevision(this@withInMemoryDatabase) shouldBe 3L
                }
            }
        }

        test("nextRevision() is strictly monotonic across concurrent calls") {
            withInMemoryDatabase {
                runTest {
                    val results = (1..50).map { nextRevision(this@withInMemoryDatabase) }
                    results.toSet().size shouldBe 50
                    results shouldBe results.sorted()
                }
            }
        }
    })
