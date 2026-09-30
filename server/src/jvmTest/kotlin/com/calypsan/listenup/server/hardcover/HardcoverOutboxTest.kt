package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val USER = "u1"
private const val T0 = 1_779_451_200_000L

private class OutboxRig(
    val sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
) {
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val outbox = HardcoverOutbox(sql, clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        listOf("book-1", "book-2").forEach { sql.seedTestBook(it) }
    }

    suspend fun connectAs(hcUserId: Long) =
        connections.save(USER, HardcoverMe(hcUserId, "reader-$hcUserId"), HardcoverTokens("at", "rt", 604_800, HARDCOVER_SCOPES))
}

private fun outboxTest(block: suspend OutboxRig.() -> Unit) = withSqlDatabase { runTest { OutboxRig(sql).block() } }

/** The outbox keeps order per book, coalesces PROGRESS, honours due times and parks unmatched books. */
class HardcoverOutboxTest :
    FunSpec({

        test("queued PROGRESS for one book collapses to the newest position, keeping its place behind START") {
            outboxTest {
                outbox.enqueueStart(USER, "book-1", listenThrough = T0, startedAt = T0, isReread = false)
                outbox.enqueueProgress(USER, "book-1", listenThrough = T0, positionSeconds = 60L, notBefore = T0)
                outbox.enqueueProgress(USER, "book-1", listenThrough = T0, positionSeconds = 600L, notBefore = T0)
                outbox.enqueueProgress(USER, "book-1", listenThrough = T0, positionSeconds = 1_800L, notBefore = T0)

                outbox.pendingFor(USER).map { it.payload } shouldBe
                    listOf(HardcoverPushPayload.Start(startedAt = T0, isReread = false), HardcoverPushPayload.Progress(positionSeconds = 1_800L))
            }
        }

        test("a book's rows run in order; another book is not held up behind it") {
            outboxTest {
                outbox.enqueueStart(USER, "book-1", T0, T0, false)
                outbox.enqueueProgress(USER, "book-1", T0, 60L, T0)
                outbox.enqueueStart(USER, "book-2", T0, T0, false)

                val first = outbox.head(USER)!!
                first.bookId shouldBe "book-1"
                outbox.reschedule(first.id, attempts = 1, nextAttemptAt = T0 + 60_000L, lastError = "probe 500")

                // book-1's START is backing off, so its PROGRESS must wait; book-2 may run.
                outbox.head(USER)!!.bookId shouldBe "book-2"
            }
        }

        test("a PROGRESS row is not due before its throttle time, and nextWakeAt says when it is") {
            outboxTest {
                outbox.enqueueProgress(USER, "book-1", T0, 60L, notBefore = T0 + 60.minutes.inWholeMilliseconds)
                outbox.head(USER).shouldBeNull()
                outbox.nextWakeAt(USER) shouldBe T0 + 60.minutes.inWholeMilliseconds
                clock.instant = Instant.fromEpochMilliseconds(T0 + 60.minutes.inWholeMilliseconds)
                outbox.head(USER)!!.payload shouldBe HardcoverPushPayload.Progress(60L)
            }
        }

        test("FINISH drops the book's queued PROGRESS") {
            outboxTest {
                outbox.enqueueProgress(USER, "book-1", T0, 60L, T0)
                outbox.enqueueFinish(USER, "book-1", listenThrough = T0, finishedAt = T0 + 1_000L)
                outbox.pendingFor(USER).map { it.payload } shouldBe listOf(HardcoverPushPayload.Finish(finishedAt = T0 + 1_000L))
            }
        }

        test("a NEEDS_MATCH book is parked; linking it by hand unparks it, due now") {
            outboxTest {
                outbox.enqueueStart(USER, "book-1", T0, T0, false)
                links.recordAutomaticMatch(USER, "book-1", null)
                outbox.head(USER).shouldBeNull()
                outbox.nextWakeAt(USER).shouldBeNull()

                links.linkManually(USER, "book-1", 427_578L, null)
                outbox.unpark(USER, "book-1")
                outbox.head(USER)!!.bookId shouldBe "book-1"
            }
        }

        test("dropping a listen-through removes only its rows") {
            outboxTest {
                outbox.enqueueFinish(USER, "book-1", listenThrough = T0, finishedAt = T0 + 1L)
                outbox.enqueueStart(USER, "book-1", listenThrough = T0 + 5L, startedAt = T0 + 5L, isReread = true)
                outbox.dropListenThrough(USER, "book-1", T0)
                outbox.pendingFor(USER).map { it.listenThrough } shouldBe listOf(T0 + 5L)
            }
        }

        test("users with pending rows are listed once each") {
            outboxTest {
                outbox.enqueueStart(USER, "book-1", T0, T0, false)
                outbox.enqueueStart(USER, "book-2", T0, T0, false)
                outbox.usersWithPending() shouldBe listOf(USER)
            }
        }

        test("disconnecting forgets the pending pushes") {
            outboxTest {
                connectAs(42L)
                outbox.enqueueFinish(USER, "book-1", listenThrough = T0, finishedAt = T0 + 1L)
                connections.delete(USER)
                outbox.pendingFor(USER) shouldBe emptyList()
            }
        }

        test("reconnecting as a different account forgets the pending pushes; the same account keeps them") {
            outboxTest {
                connectAs(42L)
                outbox.enqueueFinish(USER, "book-1", listenThrough = T0, finishedAt = T0 + 1L)
                connectAs(42L)
                outbox.pendingFor(USER).size shouldBe 1
                connectAs(99L)
                outbox.pendingFor(USER) shouldBe emptyList()
            }
        }
    })
