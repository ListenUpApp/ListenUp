package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/** The coverage rule reports its decision, so Hardcover's FINISH follows only a genuinely new read. */
class BookReadsCompletionOutcomeTest :
    FunSpec({

        test("the first finish appends; a replay with no listening in between merges and says so") {
            withSqlDatabase {
                sql.seedTestUser("u1")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-1")
                // A known length, so the coverage rule can judge the replay (a zero-length book always appends).
                driver.execute(null, "UPDATE books SET total_duration = 36000000 WHERE id = 'book-1'", 0)
                val reads = BookReadsRepository(db = sql)
                runTest {
                    reads.recordCompletion("u1", "book-1", finishedAtMs = 1_000L) shouldBe true
                    reads.recordCompletion("u1", "book-1", finishedAtMs = 2_000L) shouldBe false
                    reads.finishesForUserBook("u1", "book-1") shouldBe listOf(2_000L)
                }
            }
        }
    })
