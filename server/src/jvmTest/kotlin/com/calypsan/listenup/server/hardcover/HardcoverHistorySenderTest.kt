package com.calypsan.listenup.server.hardcover

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
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
private const val T0 = 1_779_451_200_000L
private const val DAY = 86_400_000L

private class LifecycleRig(
    val sql: ListenUpDatabase,
) {
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val activity = HardcoverSyncActivity()
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock, activity)
    val outbox = HardcoverOutbox(sql, clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val nudges = mutableListOf<String>()
    val sender = HardcoverHistorySender(sql, clock, HardcoverPushNudge { nudges += it }, activity)
    val progress = HardcoverHistoryProgress(sql, clock, activity)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook("book-1")
        sql.seedTestBook("book-2")
    }

    /** Two books of history: book-1 read twice, book-2 once — all before T0. */
    fun seedHistory() {
        sql.seedOwnRead(USER, "book-1", "r1", finishedAt = T0 - 30 * DAY)
        sql.seedOwnRead(USER, "book-1", "r2", finishedAt = T0 - 5 * DAY)
        sql.seedOwnRead(USER, "book-2", "r3", finishedAt = T0 - 2 * DAY)
    }

    suspend fun connect(hcUserId: Long = 42L): HardcoverConnection.Connected =
        connections.save(USER, HardcoverMe(hcUserId, "reader"), HardcoverTokens("at", "rt", 604_800, HARDCOVER_SCOPES))

    suspend fun history(): HardcoverHistory =
        connections.connectionState(USER).shouldBeInstanceOf<HardcoverConnection.Connected>().history

    /** Hardcover took every queued row of [bookId]: complete them and record each read as sent. */
    suspend fun deliver(bookId: String) {
        outbox.pendingFor(USER).filter { it.bookId == bookId }.forEach { row ->
            val payload = row.payload as HardcoverPushPayload.History
            sql.recordHardcoverHistoryRead(USER, payload.readId, HardcoverHistoryOutcome.SENT, at = T0)
            outbox.complete(row.id)
        }
        progress.settle(USER)
    }

    fun state() = sql.hardcoverHistoryQueries.selectHistory(USER).executeAsOneOrNull()?.state
}

private fun lifecycleTest(block: suspend LifecycleRig.() -> Unit) = withSqlDatabase { runTest { LifecycleRig(sql).block() } }

/** Spec #1540's "The offer's state": offered once, Not now, Send, Done, and the account it belongs to. */
class HardcoverHistorySenderTest :
    FunSpec({

        test("connecting with history offers it once, counting books, and Connected says so from the start") {
            lifecycleTest {
                seedHistory()

                connect().history shouldBe HardcoverHistory.Offer(bookCount = 2)
                history() shouldBe HardcoverHistory.Offer(bookCount = 2)
                state() shouldBe "OFFERED"
            }
        }

        test("connecting with no history offers nothing and writes nothing") {
            lifecycleTest {
                connect().history shouldBe HardcoverHistory.None
                state().shouldBeNull()
            }
        }

        test("Not now leaves the quiet row for as long as history is unsent") {
            lifecycleTest {
                seedHistory()
                connect()

                sender.dismiss(USER) shouldBe AppResult.Success(Unit)

                state() shouldBe "DECLINED"
                history() shouldBe HardcoverHistory.Available(bookCount = 2)
            }
        }

        test("Send queues every unsent read as HISTORY in one go, shows Sending from zero, and wakes the lane") {
            lifecycleTest {
                seedHistory()
                connect()

                sender.send(USER) shouldBe AppResult.Success(Unit)

                outbox.pendingFor(USER).map { (it.payload as HardcoverPushPayload.History).readId } shouldBe listOf("r1", "r2", "r3")
                history() shouldBe HardcoverHistory.Sending(sentBooks = 0, totalBooks = 2)
                nudges shouldBe listOf(USER)
            }
        }

        test("Sending counts a book once none of its rows wait; with none left that can run, it is Done") {
            lifecycleTest {
                seedHistory()
                connect()
                sender.send(USER)

                deliver("book-1")
                history() shouldBe HardcoverHistory.Sending(sentBooks = 1, totalBooks = 2)

                deliver("book-2")
                state() shouldBe "DONE"
                history() shouldBe HardcoverHistory.Done(sentBooks = 2, needsMatchBooks = 0)
            }
        }

        test("a book waiting for a match counts on Done; dismissing Done leaves nothing, and a second send queues nothing") {
            lifecycleTest {
                seedHistory()
                connect()
                links.recordAutomaticMatch(USER, "book-2", match = null)
                sender.send(USER)

                deliver("book-1")
                history() shouldBe HardcoverHistory.Done(sentBooks = 1, needsMatchBooks = 1)

                sender.send(USER) shouldBe AppResult.Success(Unit)
                outbox.pendingFor(USER).size shouldBe 1

                sender.dismiss(USER)
                history() shouldBe HardcoverHistory.None
            }
        }

        test("reconnecting the same account keeps the offer's state; another account is offered everything again") {
            lifecycleTest {
                seedHistory()
                connect()
                sender.send(USER)
                deliver("book-1")
                deliver("book-2")
                sender.dismiss(USER)

                connect().history shouldBe HardcoverHistory.None

                connect(hcUserId = 99L).history shouldBe HardcoverHistory.Offer(bookCount = 2)
                state() shouldBe "OFFERED"
            }
        }

        test("a disconnect mid-send stops it; reconnecting shows the quiet row, and a resend skips what arrived") {
            lifecycleTest {
                seedHistory()
                connect()
                sender.send(USER)
                deliver("book-1")

                connections.delete(USER)
                outbox.pendingFor(USER) shouldBe emptyList()
                state() shouldBe "SENDING"

                connect().history shouldBe HardcoverHistory.Available(bookCount = 1)
                state() shouldBe "DECLINED"

                sender.send(USER)
                outbox.pendingFor(USER).map { (it.payload as HardcoverPushPayload.History).readId } shouldBe listOf("r3")
            }
        }

        test("every change to the offer is announced, so a watching client's Connected is republished") {
            lifecycleTest {
                seedHistory()
                connect()
                activity.changes.test {
                    sender.dismiss(USER)
                    awaitItem() shouldBe USER
                    sender.send(USER)
                    awaitItem() shouldBe USER
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("Send without a connection is NotConnected; with a broken one, ConnectionBroken; neither queues anything") {
            lifecycleTest {
                seedHistory()
                sender.send(USER).shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<HardcoverError.NotConnected>()

                connect()
                connections.markBroken(USER, HardcoverBrokenReason.REVOKED)
                sender.send(USER).shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<HardcoverError.ConnectionBroken>()
                outbox.pendingFor(USER) shouldBe emptyList()
            }
        }
    })
