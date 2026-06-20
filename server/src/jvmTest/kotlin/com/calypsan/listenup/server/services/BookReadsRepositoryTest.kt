@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private val NOW = Instant.parse("2026-06-14T00:00:00Z")

class BookReadsRepositoryTest :
    FunSpec({
        test("recordRead appends a row and finishesForBook returns it") {
            withInMemoryDatabase {
                val repo = BookReadsRepository(db = this.asSqlDatabase(), clock = FixedClock(NOW))
                runTest {
                    repo.recordRead(id = "r1", userId = "u1", bookId = "b1", finishedAt = 100L, source = "playback")
                    repo.finishesForBook("b1") shouldContainExactly
                        listOf(BookReadRow("u1", "b1", 100L, "playback"))
                }
            }
        }

        test("re-reads stack: two rows for the same user+book, newest-first per user") {
            withInMemoryDatabase {
                val repo = BookReadsRepository(db = this.asSqlDatabase(), clock = FixedClock(NOW))
                runTest {
                    repo.recordRead("r1", "u1", "b1", finishedAt = 100L, source = "playback")
                    repo.recordRead("r2", "u1", "b1", finishedAt = 300L, source = "playback")
                    repo.finishesForUserBook("u1", "b1") shouldBe listOf(300L, 100L)
                }
            }
        }
    })
