package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val HC_BOOK = 427_578L
private const val HC_EDITION = 9_001L
private const val T0 = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC
private const val DAY = 86_400_000L

private class ExecutorRig(
    val sql: ListenUpDatabase,
    readUpdates: FakeHardcoverLibrary.ReadUpdates,
) {
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val hardcover = FakeHardcoverLibrary(readUpdates)
    val links = HardcoverBookLinkStore(sql, clock)
    val outbox = HardcoverOutbox(sql, clock)
    val executor = HardcoverPushExecutor(HardcoverUserBooks(hardcover.client()), links, outbox, sql, clock)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
    }

    suspend fun linkBook() = links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(HC_BOOK, HC_EDITION, HardcoverMatchMethod.ASIN))

    /** Runs the lane's next row; a Done row is completed, as the worker would. */
    suspend fun runHead(): PushOutcome {
        val row = outbox.head(USER)!!
        val outcome = executor.execute(row, links.linkFor(USER, BOOK)!!, "hc_at_1")
        if (outcome == PushOutcome.Done) outbox.complete(row.id)
        return outcome
    }

    fun shelf() = hardcover.shelfFor(HC_BOOK)

    suspend fun link() = links.linkFor(USER, BOOK)!!

    /** What each `update_user_book_read` sent as its `DatesReadInput`, in order. */
    fun readUpdatesSent(): List<JsonObject> =
        hardcover.requests.filter { it.operation == "update_user_book_read" }.map { it.variables.getValue("read").jsonObject }
}

private fun executorTest(
    readUpdates: FakeHardcoverLibrary.ReadUpdates = FakeHardcoverLibrary.ReadUpdates.PATCH,
    block: suspend ExecutorRig.() -> Unit,
) = withSqlDatabase { runTest { ExecutorRig(sql, readUpdates).apply { linkBook() }.block() } }

/** Spec B2's read targeting and deletion rule, one Hardcover operation at a time. */
class HardcoverPushExecutorTest :
    FunSpec({

        test("START with the book not on the shelf shelves it at the matched edition as Reading and opens a dated read") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, listenThrough = T0, startedAt = T0, isReread = false)
                runHead() shouldBe PushOutcome.Done

                val shelf = shelf()!!
                shelf.statusId shouldBe HardcoverStatus.READING
                shelf.editionId shouldBe HC_EDITION
                val read = shelf.reads.single()
                read.startedAt shouldBe "2026-05-22"
                link().hcUserBookId shouldBe shelf.id
                link().openHcReadId shouldBe read.id
                link().openReadListenThrough shouldBe T0
                links.isPushedRead(USER, read.id) shouldBe true
            }
        }

        test("START shelving a new book adopts the read Hardcover opens for Currently Reading, redated, not a second one") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, listenThrough = T0, startedAt = T0, isReread = false)
                runHead() shouldBe PushOutcome.Done

                val read = shelf()!!.reads.single()
                read.startedAt shouldBe "2026-05-22"
                read.finishedAt.shouldBeNull()
                hardcover.operations.count { it == "insert_user_book_read" } shouldBe 0
                link().openHcReadId shouldBe read.id
                links.isPushedRead(USER, read.id) shouldBe true
            }
        }

        test("a retry after the answer to shelving the book was lost redates the read Hardcover opened, as the first try would") {
            executorTest {
                hardcover.loseNextReplyTo("insert_user_book")
                outbox.enqueueStart(USER, BOOK, listenThrough = T0, startedAt = T0, isReread = false)
                runHead().shouldBeInstanceOf<PushOutcome.Failed>()
                shelf()!!.reads.single().startedAt shouldBe FAKE_TODAY

                runHead() shouldBe PushOutcome.Done

                val read = shelf()!!.reads.single()
                read.startedAt shouldBe "2026-05-22"
                read.editionId shouldBe HC_EDITION
                hardcover.operations.count { it == "insert_user_book" } shouldBe 1
                link().hcUserBookId shouldBe shelf()!!.id
                link().openHcReadId shouldBe read.id
                links.isPushedRead(USER, read.id) shouldBe true
            }
        }

        test("START finding a read already open on Hardcover continues it, records it, and sets Reading") {
            executorTest {
                val seeded = hardcover.seedShelf(HC_BOOK, statusId = 1, "2026-05-20" to null)
                outbox.enqueueStart(USER, BOOK, T0, T0, isReread = false)
                runHead() shouldBe PushOutcome.Done

                shelf()!!.reads.size shouldBe 1
                shelf()!!.statusId shouldBe HardcoverStatus.READING
                link().openHcReadId shouldBe seeded.reads.single().id
                links.isPushedRead(USER, seeded.reads.single().id) shouldBe true
            }
        }

        test("START after a finished read opens a new one") {
            executorTest {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-02-03")
                outbox.enqueueStart(USER, BOOK, T0, T0, isReread = false)
                runHead() shouldBe PushOutcome.Done
                shelf()!!.reads.map { it.startedAt } shouldBe listOf("2017-01-02", "2026-05-22")
                shelf()!!.statusId shouldBe HardcoverStatus.READING
            }
        }

        test("a re-read after ListenUp's own finish opens a second read") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, T0, T0, isReread = false)
                runHead()
                outbox.enqueueFinish(USER, BOOK, T0, finishedAt = T0 + DAY)
                runHead()
                val rereadAt = T0 + 30 * DAY
                outbox.enqueueStart(USER, BOOK, rereadAt, rereadAt, isReread = true)
                runHead() shouldBe PushOutcome.Done

                shelf()!!.reads.map { it.finishedAt } shouldBe listOf("2026-05-23", null)
                shelf()!!.statusId shouldBe HardcoverStatus.READING
                link().openReadListenThrough shouldBe rereadAt
            }
        }

        test("PROGRESS writes the position to the open read and starts the throttle") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead()
                outbox.enqueueProgress(USER, BOOK, T0, positionSeconds = 5_400L, notBefore = T0)
                runHead() shouldBe PushOutcome.Done
                shelf()!!.reads.single().progressSeconds shouldBe 5_400L
                link().lastProgressPushedAt shouldBe T0
            }
        }

        test(
            "PROGRESS with nothing open (connected mid-book) shelves the book and keeps Hardcover's date on its read",
        ) {
            executorTest {
                outbox.enqueueProgress(USER, BOOK, LEGACY_LISTEN_THROUGH, positionSeconds = 60L, notBefore = T0)
                runHead() shouldBe PushOutcome.Done
                val read = shelf()!!.reads.single()
                read.startedAt shouldBe FAKE_TODAY
                read.progressSeconds shouldBe 60L
                shelf()!!.statusId shouldBe HardcoverStatus.READING
            }
        }

        test("FINISH dates the read in the user's timezone, sets Read, and closes the open read") {
            executorTest {
                sql.usersQueries.updateTimezone(timezone = "Pacific/Auckland", id = USER)
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead()
                outbox.enqueueFinish(USER, BOOK, T0, finishedAt = T0) // 2026-05-22 12:00 UTC is the 23rd in Auckland
                runHead() shouldBe PushOutcome.Done

                shelf()!!.reads.single().finishedAt shouldBe "2026-05-23"
                shelf()!!.statusId shouldBe HardcoverStatus.READ
                link().openHcReadId.shouldBeNull()
            }
        }

        test("PROGRESS and FINISH never send a lone field: each update carries the read's start date and known state") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead()
                outbox.enqueueProgress(USER, BOOK, T0, positionSeconds = 5_400L, notBefore = T0)
                runHead()
                outbox.enqueueFinish(USER, BOOK, T0, finishedAt = T0 + DAY)
                runHead()

                val (progress, finish) = readUpdatesSent().takeLast(2)
                progress.getValue("started_at").jsonPrimitive.content shouldBe "2026-05-22"
                progress.getValue("progress_seconds").jsonPrimitive.long shouldBe 5_400L
                progress.getValue("edition_id").jsonPrimitive.long shouldBe HC_EDITION
                finish.getValue("started_at").jsonPrimitive.content shouldBe "2026-05-22"
                finish.getValue("finished_at").jsonPrimitive.content shouldBe "2026-05-23"
                finish.getValue("progress_seconds").jsonPrimitive.long shouldBe 5_400L
            }
        }

        test("a continued read keeps the start date Hardcover already had") {
            executorTest {
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READING, "2026-05-20" to null)
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead()
                outbox.enqueueProgress(USER, BOOK, T0, positionSeconds = 60L, notBefore = T0)
                runHead()
                readUpdatesSent()
                    .single()
                    .getValue("started_at")
                    .jsonPrimitive.content shouldBe "2026-05-20"
            }
        }

        test("if Hardcover replaces a read on update, a whole listen-through still lands intact") {
            executorTest(FakeHardcoverLibrary.ReadUpdates.REPLACE) {
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead() shouldBe PushOutcome.Done
                outbox.enqueueProgress(USER, BOOK, T0, positionSeconds = 5_400L, notBefore = T0)
                runHead() shouldBe PushOutcome.Done
                shelf()!!.reads.single().startedAt shouldBe "2026-05-22"
                outbox.enqueueFinish(USER, BOOK, T0, finishedAt = T0 + DAY)
                runHead() shouldBe PushOutcome.Done

                val read = shelf()!!.reads.single()
                read.startedAt shouldBe "2026-05-22"
                read.progressSeconds shouldBe 5_400L
                read.finishedAt shouldBe "2026-05-23"
                read.editionId shouldBe HC_EDITION
            }
        }

        test("the deletion rule: the shelf entry deleted on Hardcover suppresses the listen-through, finish included") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead()
                hardcover.deleteShelf(HC_BOOK)
                outbox.enqueueProgress(USER, BOOK, T0, 600L, T0)
                outbox.enqueueFinish(USER, BOOK, T0, T0 + DAY)

                runHead() shouldBe PushOutcome.Suppressed

                outbox.pendingFor(USER).shouldBeEmpty()
                shelf().shouldBeNull()
                link().suppressedListenThrough shouldBe T0
                link().hcUserBookId.shouldBeNull()
            }
        }

        test("the deletion rule: the read alone deleted on Hardcover suppresses too") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead()
                hardcover.deleteRead(link().openHcReadId!!)
                outbox.enqueueProgress(USER, BOOK, T0, 600L, T0)
                runHead() shouldBe PushOutcome.Suppressed
                shelf()!!.reads.shouldBeEmpty()
            }
        }

        test("a new listen-through after a suppression syncs again, from a fresh shelf entry") {
            executorTest {
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead()
                hardcover.deleteShelf(HC_BOOK)
                outbox.enqueueFinish(USER, BOOK, T0, T0 + DAY)
                runHead() shouldBe PushOutcome.Suppressed

                val rereadAt = T0 + 30 * DAY
                links.clearSuppressionUnlessFor(USER, BOOK, rereadAt)
                outbox.enqueueStart(USER, BOOK, rereadAt, rereadAt, isReread = true)
                runHead() shouldBe PushOutcome.Done
                shelf()!!.reads.single().startedAt shouldBe "2026-06-21"
            }
        }

        test("a row of an already-suppressed listen-through is dropped without asking Hardcover") {
            executorTest {
                links.suppress(USER, BOOK, T0)
                outbox.enqueueFinish(USER, BOOK, T0, T0 + DAY)
                runHead() shouldBe PushOutcome.Suppressed
                hardcover.operations.shouldBeEmpty()
                outbox.pendingFor(USER).shouldBeEmpty()
            }
        }

        test("a failure is returned for the worker's policy, and nothing is recorded") {
            executorTest {
                hardcover.failNext(FakeReply(HttpStatusCode.InternalServerError))
                outbox.enqueueStart(USER, BOOK, T0, T0, false)
                runHead().shouldBeInstanceOf<PushOutcome.Failed>().failure shouldBe HardcoverCall.Failed("userBookFor 500")
                link().openHcReadId.shouldBeNull()
                outbox.pendingFor(USER).size shouldBe 1
            }
        }

        test("Only when I finish: a FINISH with nothing before it shelves the book once, dates the read from the listen-through, and marks it Read") {
            executorTest(FakeHardcoverLibrary.ReadUpdates.REPLACE) {
                outbox.enqueueFinish(USER, BOOK, listenThrough = T0, finishedAt = T0 + 3 * DAY)
                runHead() shouldBe PushOutcome.Done

                val shelf = shelf()!!
                shelf.statusId shouldBe HardcoverStatus.READ
                shelf.editionId shouldBe HC_EDITION
                val read = shelf.reads.single()
                read.startedAt shouldBe "2026-05-22"
                read.finishedAt shouldBe "2026-05-25"
                read.progressSeconds.shouldBeNull()
                read.editionId shouldBe HC_EDITION
                hardcover.operations.count { it == "insert_user_book" } shouldBe 1
                hardcover.operations.count { it == "insert_user_book_read" } shouldBe 0
                links.isPushedRead(USER, read.id) shouldBe true
                link().openHcReadId.shouldBeNull()
            }
        }

        test("Only when I finish: a FINISH whose answer to shelving was lost still lands one entry with the real dates") {
            executorTest(FakeHardcoverLibrary.ReadUpdates.REPLACE) {
                hardcover.loseNextReplyTo("insert_user_book")
                outbox.enqueueFinish(USER, BOOK, listenThrough = T0, finishedAt = T0 + 3 * DAY)
                runHead().shouldBeInstanceOf<PushOutcome.Failed>()

                runHead() shouldBe PushOutcome.Done

                hardcover.operations.count { it == "insert_user_book" } shouldBe 1
                val read = shelf()!!.reads.single()
                read.startedAt shouldBe "2026-05-22"
                read.finishedAt shouldBe "2026-05-25"
                shelf()!!.statusId shouldBe HardcoverStatus.READ
            }
        }

        test("a FINISH from before listen-throughs knows no start, so the read keeps the date Hardcover gave it") {
            executorTest(FakeHardcoverLibrary.ReadUpdates.REPLACE) {
                outbox.enqueueFinish(USER, BOOK, listenThrough = LEGACY_LISTEN_THROUGH, finishedAt = T0 + 3 * DAY)
                runHead() shouldBe PushOutcome.Done

                val read = shelf()!!.reads.single()
                read.startedAt shouldBe FAKE_TODAY
                read.finishedAt shouldBe "2026-05-25"
                shelf()!!.statusId shouldBe HardcoverStatus.READ
            }
        }

        test("As I listen chosen again mid-book: a PROGRESS with no START before it shelves the book and dates the read from the listen-through") {
            executorTest(FakeHardcoverLibrary.ReadUpdates.REPLACE) {
                outbox.enqueueProgress(USER, BOOK, listenThrough = T0, positionSeconds = 390L, notBefore = T0)
                runHead() shouldBe PushOutcome.Done

                val shelf = shelf()!!
                shelf.statusId shouldBe HardcoverStatus.READING
                val read = shelf.reads.single()
                read.startedAt shouldBe "2026-05-22"
                read.progressSeconds shouldBe 390L
                read.editionId shouldBe HC_EDITION
                hardcover.operations.count { it == "insert_user_book_read" } shouldBe 0
                link().openHcReadId shouldBe read.id
            }
        }
    })
