package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private const val USER = "u1"
private const val OTHER = "u2"
private const val T0 = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC — the moment the user connected
private const val DAY = 86_400_000L

private class SelectionRig(
    val sql: ListenUpDatabase,
) {
    init {
        sql.seedTestUser(USER)
        sql.seedTestUser(OTHER)
        sql.seedTestLibraryAndFolder()
        listOf("book-1", "book-2", "book-3").forEach { sql.seedTestBook(it) }
    }

    suspend fun unsent() = sql.unsentHardcoverHistory(USER, connectedAt = T0)
}

private fun selectionTest(block: suspend SelectionRig.() -> Unit) = withSqlDatabase { runTest { SelectionRig(sql).block() } }

/** Spec #1540's "Which reads are history": own reads, before the connection, not yet handled, each with its start. */
class HardcoverHistoryStoreTest :
    FunSpec({

        test("own reads finished before connecting are history; pulled reads, later reads and other users' are not") {
            selectionTest {
                sql.seedOwnRead(USER, "book-1", "own-before", finishedAt = T0 - 20 * DAY)
                sql.seedPulledRead(USER, "book-2", hcReadId = 7L, finishedAt = T0 - 3 * DAY)
                sql.seedOwnRead(USER, "book-3", "own-after", finishedAt = T0 + DAY)
                sql.seedOwnRead(OTHER, "book-1", "someone-else", finishedAt = T0 - 20 * DAY)

                unsent().map { it.readId } shouldBe listOf("own-before")
            }
        }

        test("a book kept off Hardcover is not history: not offered, not counted") {
            selectionTest {
                sql.seedOwnRead(USER, "book-1", "kept", finishedAt = T0 - 20 * DAY)
                sql.seedOwnRead(USER, "book-2", "shared", finishedAt = T0 - 10 * DAY)
                sql.seedExclusion(USER, "book-1", at = T0 - DAY)

                unsent().map { it.readId } shouldBe listOf("shared")
                sql.unsentHardcoverHistoryBooks(USER, connectedAt = T0) shouldBe 1
            }
        }

        test("each read starts at the earliest listening after the previous own read finished — rereads included") {
            selectionTest {
                sql.seedListeningEvent(USER, "book-1", "e1", startedAt = T0 - 40 * DAY)
                sql.seedListeningEvent(USER, "book-1", "e2", startedAt = T0 - 35 * DAY)
                sql.seedOwnRead(USER, "book-1", "first", finishedAt = T0 - 30 * DAY)
                sql.seedListeningEvent(USER, "book-1", "e3", startedAt = T0 - 10 * DAY)
                sql.seedListeningEvent(USER, "book-1", "e4", startedAt = T0 - 8 * DAY)
                sql.seedOwnRead(USER, "book-1", "reread", finishedAt = T0 - 5 * DAY)
                // Listening after the reread finished belongs to no read here.
                sql.seedListeningEvent(USER, "book-1", "e5", startedAt = T0 - 2 * DAY)

                unsent() shouldBe
                    listOf(
                        HardcoverHistoryRead("first", "book-1", startedAt = T0 - 40 * DAY, finishedAt = T0 - 30 * DAY),
                        HardcoverHistoryRead("reread", "book-1", startedAt = T0 - 10 * DAY, finishedAt = T0 - 5 * DAY),
                    )
            }
        }

        test("a read with no listening before it goes with its finish date alone") {
            selectionTest {
                sql.seedOwnRead(USER, "book-2", "imported", finishedAt = T0 - 2 * DAY)

                unsent().single().startedAt.shouldBeNull()
            }
        }

        test("a read in the ledger is not history again, and the count is of distinct books") {
            selectionTest {
                sql.seedOwnRead(USER, "book-1", "first", finishedAt = T0 - 30 * DAY)
                sql.seedOwnRead(USER, "book-1", "reread", finishedAt = T0 - 5 * DAY)
                sql.seedOwnRead(USER, "book-2", "other", finishedAt = T0 - 2 * DAY)
                sql.unsentHardcoverHistoryBooks(USER, connectedAt = T0) shouldBe 2

                sql.recordHardcoverHistoryRead(USER, "other", HardcoverHistoryOutcome.SENT, at = T0)

                unsent().map { it.readId } shouldBe listOf("first", "reread")
                sql.unsentHardcoverHistoryBooks(USER, connectedAt = T0) shouldBe 1
            }
        }

        test("ListenUp's reads of a book through one read count every earlier own read and that one") {
            selectionTest {
                sql.seedOwnRead(USER, "book-1", "first", finishedAt = T0 - 30 * DAY)
                sql.seedOwnRead(USER, "book-1", "reread", finishedAt = T0 - 5 * DAY)
                sql.seedPulledRead(USER, "book-1", hcReadId = 9L, finishedAt = T0 - 20 * DAY)

                sql.ownReadsThrough(USER, "book-1", finishedAt = T0 - 30 * DAY, readId = "first") shouldBe 1
                sql.ownReadsThrough(USER, "book-1", finishedAt = T0 - 5 * DAY, readId = "reread") shouldBe 2
            }
        }

        test("the ledger keeps the last outcome per read, and quietly records nothing for a read that is gone") {
            selectionTest {
                sql.seedOwnRead(USER, "book-1", "first", finishedAt = T0 - 30 * DAY)

                sql.recordHardcoverHistoryRead(USER, "first", HardcoverHistoryOutcome.ALREADY_THERE, at = T0)
                sql.recordHardcoverHistoryRead(USER, "first", HardcoverHistoryOutcome.SENT, at = T0 + 1)
                sql.recordHardcoverHistoryRead(USER, "vanished", HardcoverHistoryOutcome.SENT, at = T0)

                sql.hardcoverHistoryQueries.outcomeOf(USER, "first").executeAsOneOrNull() shouldBe "SENT"
                sql.hardcoverHistoryQueries
                    .outcomeOf(USER, "vanished")
                    .executeAsOneOrNull()
                    .shouldBeNull()
            }
        }
    })
