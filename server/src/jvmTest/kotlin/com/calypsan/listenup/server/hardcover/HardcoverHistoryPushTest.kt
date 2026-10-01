package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val HC_BOOK = 427_578L
private const val HC_EDITION = 9_001L
private const val T0 = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
private const val DAY = 86_400_000L

// The read under test: begun 2026-04-22, finished 2026-05-02 (UTC). A reread: 2026-05-12 to 2026-05-17.
private const val STARTED = T0 - 30 * DAY
private const val FINISHED = T0 - 20 * DAY
private const val REREAD_STARTED = T0 - 10 * DAY
private const val REREAD_FINISHED = T0 - 5 * DAY

private class HistoryRig(
    val sql: ListenUpDatabase,
    readUpdates: FakeHardcoverLibrary.ReadUpdates,
    opensReadOnStatusChange: Boolean,
) {
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val hardcover = FakeHardcoverLibrary(readUpdates, opensReadOnStatusChange = opensReadOnStatusChange)
    val links = HardcoverBookLinkStore(sql, clock)
    val outbox = HardcoverOutbox(sql, clock)
    val executor = HardcoverPushExecutor(HardcoverUserBooks(hardcover.client()), links, outbox, sql, clock, NoWaitRateLimiter())

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
    }

    suspend fun linkBook() = links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(HC_BOOK, HC_EDITION, HardcoverMatchMethod.ASIN))

    /** The user finished [readId] in ListenUp, and it is queued as history. */
    suspend fun queueHistory(
        readId: String,
        startedAt: Long?,
        finishedAt: Long,
    ) {
        sql.seedOwnRead(USER, BOOK, readId, finishedAt)
        outbox.enqueueHistory(USER, HardcoverHistoryRead(readId, BOOK, startedAt, finishedAt))
    }

    /** Runs the lane's next row; a Done row is completed, as the worker would. */
    suspend fun runHead(): PushOutcome {
        val row = outbox.head(USER)!!
        val outcome = executor.execute(row, links.linkFor(USER, BOOK)!!, "hc_at_1")
        if (outcome == PushOutcome.Done) outbox.complete(row.id)
        return outcome
    }

    fun shelf() = hardcover.shelfFor(HC_BOOK)

    fun dates() = shelf()!!.reads.map { it.startedAt to it.finishedAt }

    fun outcomeOf(readId: String) = sql.hardcoverHistoryQueries.outcomeOf(USER, readId).executeAsOneOrNull()

    fun mutations() = hardcover.operations.filter { it.startsWith("insert_") || it.startsWith("update_") }
}

private fun historyTest(
    readUpdates: FakeHardcoverLibrary.ReadUpdates = FakeHardcoverLibrary.ReadUpdates.PATCH,
    opensReadOnStatusChange: Boolean = false,
    block: suspend HistoryRig.() -> Unit,
) = withSqlDatabase { runTest { HistoryRig(sql, readUpdates, opensReadOnStatusChange).apply { linkBook() }.block() } }

/** Spec #1540's "Running a HISTORY row", with Simon's 2026-10-01 decisions: against the fake Hardcover, one shelf state at a time. */
class HardcoverHistoryPushTest :
    FunSpec({

        test("not on the shelf: one Read entry at the matched edition, its one read dated from start to finish") {
            historyTest {
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                val shelf = shelf()!!
                shelf.statusId shouldBe HardcoverStatus.READ
                shelf.editionId shouldBe HC_EDITION
                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
                hardcover.operations.count { it == "insert_user_book_read" } shouldBe 0
                links.isPushedRead(USER, shelf.reads.single().id) shouldBe true
                links.isLivePushedRead(USER, shelf.reads.single().id) shouldBe false
                outcomeOf("r1") shouldBe "SENT"
            }
        }

        test("not on the shelf, under a Hardcover that replaces reads wholesale, the dates still survive") {
            historyTest(FakeHardcoverLibrary.ReadUpdates.REPLACE) {
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
            }
        }

        test("not on the shelf with no start known: the read starts on its finish day, never on today") {
            historyTest {
                queueHistory("r1", startedAt = null, finishedAt = FINISHED)

                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-05-02" to "2026-05-02")
            }
        }

        test("Currently Reading: the open read is finished, redated when Hardcover's start is later — not a second read") {
            historyTest {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READING, "2026-05-10" to null)
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                shelf()!!.statusId shouldBe HardcoverStatus.READ
                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
                hardcover.operations.count { it == "insert_user_book_read" } shouldBe 0
                outcomeOf("r1") shouldBe "SENT"
            }
        }

        test("Currently Reading since earlier than ListenUp knows: Hardcover's start is kept") {
            historyTest {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READING, "2026-01-05" to null)
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-01-05" to "2026-05-02")
            }
        }

        test("Want to Read with no read: one read is added with both dates, and the book becomes Read") {
            historyTest {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.WANT_TO_READ)
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                shelf()!!.statusId shouldBe HardcoverStatus.READ
                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
                hardcover.operations.count { it == "insert_user_book_read" } shouldBe 1
            }
        }

        test("a Hardcover that opens a read when a book becomes Read gets that read dated — never a second one") {
            historyTest(opensReadOnStatusChange = true) {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.WANT_TO_READ)
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                shelf()!!.statusId shouldBe HardcoverStatus.READ
                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
                hardcover.operations.count { it == "insert_user_book_read" } shouldBe 0
            }
        }

        test("Did Not Finish, Paused, or any status other than Want to Read, Currently Reading and Read: left as Hardcover has it") {
            listOf(4, 5, 6).forEach { status ->
                historyTest {
                    hardcover.seedShelf(HC_BOOK, status)
                    queueHistory("r1", STARTED, FINISHED)

                    runHead() shouldBe PushOutcome.Done

                    mutations() shouldBe emptyList()
                    shelf()!!.statusId shouldBe status
                    outcomeOf("r1") shouldBe "ALREADY_THERE"
                }
            }
        }

        test("already Read, even with another date or with no read at all: skipped, nothing written") {
            listOf(
                listOf<Pair<String?, String?>>("2019-01-01" to "2019-02-01"),
                emptyList(),
            ).forEach { reads ->
                historyTest {
                    hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, *reads.toTypedArray())
                    queueHistory("r1", STARTED, FINISHED)

                    runHead() shouldBe PushOutcome.Done

                    mutations() shouldBe emptyList()
                    outcomeOf("r1") shouldBe "ALREADY_THERE"
                }
            }
        }

        test("a reread beyond Hardcover's count is added; the first read, already there, is not") {
            historyTest {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2026-04-22" to "2026-05-02")
                queueHistory("r1", STARTED, FINISHED)
                queueHistory("r2", REREAD_STARTED, REREAD_FINISHED)

                runHead() shouldBe PushOutcome.Done
                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-04-22" to "2026-05-02", "2026-05-12" to "2026-05-17")
                outcomeOf("r1") shouldBe "ALREADY_THERE"
                outcomeOf("r2") shouldBe "SENT"
            }
        }

        test("a reread ListenUp pushed live after connecting doesn't make the earlier history read look already there") {
            historyTest {
                val liveReread = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2026-05-20" to "2026-05-21").reads.single()
                links.recordPushedRead(USER, liveReread.id, BOOK)
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-05-20" to "2026-05-21", "2026-04-22" to "2026-05-02")
                outcomeOf("r1") shouldBe "SENT"
            }
        }

        test("a retry after the answer to shelving was lost finishes the read Hardcover opened — one entry, one read") {
            historyTest {
                hardcover.loseNextReplyTo("insert_user_book")
                queueHistory("r1", STARTED, FINISHED)

                runHead().shouldBeInstanceOf<PushOutcome.Failed>()
                runHead() shouldBe PushOutcome.Done

                hardcover.operations.count { it == "insert_user_book" } shouldBe 1
                shelf()!!.statusId shouldBe HardcoverStatus.READ
                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
            }
        }

        test("a retry after the answer to Read was lost finishes the read Hardcover opened, adding nothing") {
            historyTest {
                hardcover.loseNextReplyTo("update_user_book")
                queueHistory("r1", STARTED, FINISHED)

                runHead().shouldBeInstanceOf<PushOutcome.Failed>()
                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
                shelf()!!.statusId shouldBe HardcoverStatus.READ
                outcomeOf("r1") shouldBe "SENT"
            }
        }

        test("a retry after the answer to dating the read was lost adds nothing: the finished read is recognised as ListenUp's") {
            historyTest {
                hardcover.loseNextReplyTo("update_user_book_read")
                queueHistory("r1", STARTED, FINISHED)

                runHead().shouldBeInstanceOf<PushOutcome.Failed>()
                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
                shelf()!!.statusId shouldBe HardcoverStatus.READ
                outcomeOf("r1") shouldBe "SENT"
            }
        }

        test("a retry after the answer to adding a reread was lost does not add it twice") {
            historyTest {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2026-04-22" to "2026-05-02")
                queueHistory("r1", STARTED, FINISHED)
                runHead() shouldBe PushOutcome.Done
                hardcover.loseNextReplyTo("insert_user_book_read")
                queueHistory("r2", REREAD_STARTED, REREAD_FINISHED)

                runHead().shouldBeInstanceOf<PushOutcome.Failed>()
                runHead() shouldBe PushOutcome.Done

                shelf()!!.reads.size shouldBe 2
            }
        }

        test("a HISTORY row ignores the deletion rule: a suppressed listen-through with the same start silences nothing") {
            historyTest {
                links.suppress(USER, BOOK, STARTED)
                queueHistory("r1", STARTED, FINISHED)

                runHead() shouldBe PushOutcome.Done

                dates() shouldBe listOf("2026-04-22" to "2026-05-02")
            }
        }

        test("a live FINISH records the read it finished as sent, so a later connection never offers it again") {
            historyTest {
                sql.seedOwnRead(USER, BOOK, "older", finishedAt = T0 - 30 * DAY)
                sql.seedOwnRead(USER, BOOK, "live", finishedAt = T0 - DAY)
                outbox.enqueueFinish(USER, BOOK, listenThrough = T0 - 3 * DAY, finishedAt = T0 - DAY)

                runHead() shouldBe PushOutcome.Done

                outcomeOf("live") shouldBe "SENT"
                outcomeOf("older").shouldBeNull()
                sql.unsentHardcoverHistory(USER, connectedAt = T0 + DAY).map { it.readId } shouldBe listOf("older")
                links.isLivePushedRead(USER, shelf()!!.reads.single().id) shouldBe true
            }
        }
    })
