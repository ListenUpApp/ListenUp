package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.services.deriveUserStats
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val HC_BOOK = 427_578L
private const val T0 = 1_779_451_200_000L

/** Moves the rig's clock past the full-pull interval, so the next pull re-reads the whole shelf. */
private fun PullRig.aDayLater() {
    clock.instant =
        Instant.fromEpochMilliseconds(
            clock.now().toEpochMilliseconds() + FULL_PULL_INTERVAL.inWholeMilliseconds + 1.minutes.inWholeMilliseconds,
        )
}

/** Runs every due outbox row through the real push executor, as the push lane would. */
private suspend fun PullRig.pushAll() {
    val executor = HardcoverPushExecutor(userBooks, links, outbox, sql, clock)
    while (true) {
        val row = outbox.head(USER) ?: break
        executor.execute(row, links.linkFor(USER, BOOK)!!, "hc_at_1") shouldBe PushOutcome.Done
        outbox.complete(row.id)
    }
}

/** The `after` of every changed-since request from the [from]th on. */
private fun PullRig.aftersFrom(from: Int): List<String> =
    hardcover.requests
        .drop(from)
        .filter { it.operation == "user_books_changed" }
        .map {
            it.variables
                .getValue("after")
                .jsonPrimitive.content
        }

/** Spec B3's targeted specs, end to end through the real push executor and puller against one fake Hardcover. */
class HardcoverPullSpecsTest :
    FunSpec({

        test("no echo: a read ListenUp pushed, started and finished, is never pulled back") {
            pullTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.ASIN))
                outbox.enqueueStart(USER, BOOK, listenThrough = T0, startedAt = T0, isReread = false)
                outbox.enqueueFinish(USER, BOOK, T0, T0 + 1.hours.inWholeMilliseconds)
                pushAll()
                hardcover
                    .shelfFor(HC_BOOK)!!
                    .reads
                    .single()
                    .finishedAt
                    .shouldNotBeNull()

                pullAll()

                store.pulledReads(USER) shouldBe emptyList()
            }
        }

        test("no echo: a read ListenUp opened but the user finished on Hardcover is not pulled either") {
            pullTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.ASIN))
                outbox.enqueueStart(USER, BOOK, listenThrough = T0, startedAt = T0, isReread = false)
                pushAll()
                val opened =
                    hardcover
                        .shelfFor(HC_BOOK)!!
                        .reads
                        .single()
                hardcover.editRead(opened.id) { it.finishedAt = "2026-05-20" }

                pullAll()

                store.pulledReads(USER) shouldBe emptyList()
            }
        }

        test("no echo, but no blind spot either: a read logged beside ListenUp's own is pulled") {
            pullTest {
                connect()
                val shelf =
                    hardcover.seedShelf(
                        HC_BOOK,
                        HardcoverStatus.READ,
                        "2016-01-01" to "2016-02-01",
                        "2026-05-01" to "2026-05-20",
                        editionId = 9_001L,
                    )
                val (paperback, listened) = shelf.reads
                links.recordPushedRead(USER, listened.id, BOOK)

                pullAll()

                store.pulledReads(USER) shouldBe listOf(PulledReadRow(BOOK, noonUtc("2016-02-01"), paperback.id))
            }
        }

        test("pulling twice is idempotent, incrementally and in full") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                val once = store.pulledReads(USER)

                pullAll()
                store.pulledReads(USER) shouldBe once

                store.requestFullPull(USER)
                pullAll()
                store.pulledReads(USER) shouldBe once
                once.size shouldBe 1
            }
        }

        test("reconnecting the same account restarts the pull from the beginning, keeps the reads and duplicates none") {
            pullTest {
                connect(hcUserId = 42)
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                val once = store.pulledReads(USER)
                once.size shouldBe 1

                connect(hcUserId = 42)
                store.pullState(USER) shouldBe HardcoverPullState(null, null, fullPullStartedAt = null, lastFullPullAt = null)
                store.pulledReads(USER) shouldBe once

                val before = hardcover.requests.size
                pullAll()

                aftersFrom(before).first() shouldBe PULL_EPOCH
                store.pulledReads(USER) shouldBe once
                sql.bookReadsQueries
                    .finishesForBook(BOOK)
                    .executeAsList()
                    .size shouldBe 1
            }
        }

        test("reconnecting as a different Hardcover account removes the old account's pulled reads and its links") {
            pullTest {
                connect(hcUserId = 42)
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                store.pulledReads(USER).size shouldBe 1

                connect(hcUserId = 99)

                store.pulledReads(USER) shouldBe emptyList()
                links.linkFor(USER, BOOK).shouldBeNull()
                sql.bookReadsQueries.finishesForBook(BOOK).executeAsList() shouldBe emptyList()
            }
        }

        test("pulled reads count for nothing: books finished, streak, feed and push all stay empty") {
            pullTest {
                connect()
                // Finished today, so it would extend a streak if it counted.
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2026-05-01" to "2026-05-22", editionId = 9_001L)
                pullAll()
                store.pulledReads(USER).size shouldBe 1

                val stats = deriveUserStats(sql = sql, userId = USER, nowMs = T0)
                stats.booksFinished shouldBe 0
                stats.currentStreakDays shouldBe 0
                sql.activitiesQueries.hasAnyForUser(USER).executeAsOne() shouldBe false
                outbox.pendingFor(USER) shouldBe emptyList()
            }
        }

        test("a date changed on Hardcover moves the pulled read") {
            pullTest {
                connect()
                val shelf = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                hardcover.editRead(shelf.reads.single().id) { it.finishedAt = "2016-03-15" }
                pullAll()
                store.pulledReads(USER).single().finishedAt shouldBe noonUtc("2016-03-15")
            }
        }

        test("deletion mirroring: a read deleted on Hardcover goes at the next pull when Hardcover marks its shelf changed") {
            pullTest(readChangesTouchShelf = true) {
                connect()
                val shelf = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                hardcover.deleteRead(shelf.reads.single().id)
                pullAll()
                store.pulledReads(USER) shouldBe emptyList()
            }
        }

        test("deletion mirroring: when Hardcover doesn't mark the shelf, the next full pull catches it") {
            pullTest(readChangesTouchShelf = false) {
                connect()
                val shelf = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                hardcover.deleteRead(shelf.reads.single().id)
                pullAll()
                store.pulledReads(USER).size shouldBe 1

                aDayLater()
                pullAll()
                store.pulledReads(USER) shouldBe emptyList()
            }
        }

        test("a read moved into the future on Hardcover is treated as gone: the next full pull removes it") {
            pullTest(readChangesTouchShelf = false) {
                connect()
                val shelf = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                hardcover.editRead(shelf.reads.single().id) { it.finishedAt = "2026-12-18" }
                pullAll()
                store.pulledReads(USER).size shouldBe 1

                aDayLater()
                pullAll()
                store.pulledReads(USER) shouldBe emptyList()
            }
        }

        test("a read finished in the future comes in on the first pull once its date is real") {
            pullTest {
                connect()
                val shelf = hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2026-05-01" to "2026-05-25", editionId = 9_001L)
                pullAll()
                store.pulledReads(USER) shouldBe emptyList()

                // 2026-05-25 00:30 UTC: the finish date is today now.
                clock.instant = Instant.fromEpochMilliseconds(T0 + 60.hours.inWholeMilliseconds + 30.minutes.inWholeMilliseconds)
                pullAll()
                store.pulledReads(USER) shouldBe listOf(PulledReadRow(BOOK, noonUtc("2026-05-25"), shelf.reads.single().id))
            }
        }

        test("deletion mirroring: a whole shelf entry deleted on Hardcover goes at the next full pull; ListenUp's own read stays") {
            pullTest {
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2016-01-01" to "2016-02-01", editionId = 9_001L)
                pullAll()
                sql.bookReadsQueries.insert(
                    id = "lu",
                    user_id = USER,
                    book_id = BOOK,
                    finished_at = T0,
                    source = "playback",
                    created_at = T0,
                )
                hardcover.deleteShelf(HC_BOOK)

                pullAll()
                store.pulledReads(USER).size shouldBe 1

                aDayLater()
                pullAll()
                store.pulledReads(USER) shouldBe emptyList()
                sql.bookReadsQueries.finishesForUserBook(USER, BOOK).executeAsList() shouldBe listOf(T0)
            }
        }
    })
