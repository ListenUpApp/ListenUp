package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.shouldSucceed
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val HC_BOOK = 427_578L
private const val T0 = 1_779_451_200_000L
private const val DAY = 86_400_000L

/** [HardcoverKeepOff] over a [PullRig]'s real stores — its shelves, its Want to Read, its pull — and a real gate. */
private class KeepOffRig(
    val rig: PullRig,
) {
    val activity = HardcoverSyncActivity()
    val exclusions = HardcoverExclusions(rig.sql)
    val keepOff =
        HardcoverKeepOff(
            sql = rig.sql,
            access = BookAccessPolicy(rig.sql, rig.dbs.driver),
            wantToRead = rig.wantToRead,
            gate = HardcoverUserGate(),
            bus = rig.bus,
            history = HardcoverHistoryProgress(rig.sql, rig.clock),
            activity = activity,
            clock = rig.clock,
        )

    suspend fun keepOff(bookId: String = BOOK) = keepOff.setSynced(USER, UserRole.ROOT, bookId, synced = false)

    suspend fun syncAgain(bookId: String = BOOK) = keepOff.setSynced(USER, UserRole.ROOT, bookId, synced = true)

    suspend fun link() = rig.links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.ASIN))
}

private fun keepOffTest(block: suspend KeepOffRig.() -> Unit) = pullTest { KeepOffRig(this).block() }

/** #1541: keeping one book off Hardcover — nothing on Hardcover changes; ListenUp tidies what came from it. */
class HardcoverKeepOffTest :
    FunSpec({

        test("keeping a book off drops its queued pushes, keeps its link, and says nothing to Hardcover") {
            keepOffTest {
                rig.connect()
                link()
                rig.outbox.enqueueStart(USER, BOOK, listenThrough = T0, startedAt = T0, isReread = false)
                rig.sql.seedTestBook("book-2")
                rig.outbox.enqueueStart(USER, "book-2", listenThrough = T0, startedAt = T0, isReread = false)

                keepOff() shouldBe AppResult.Success(Unit)

                exclusions.isExcluded(USER, BOOK) shouldBe true
                rig.outbox.pendingFor(USER).map { it.bookId } shouldBe listOf("book-2")
                rig.links.linkFor(USER, BOOK)!!.hcBookId shouldBe HC_BOOK
                rig.hardcover.operations shouldBe emptyList()
            }
        }

        test("its Hardcover reads leave Readers, and every client is told to look again") {
            keepOffTest {
                rig.connect()
                rig.hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 9_001L)
                rig.pullAll()
                rig.store.pulledReads(USER).size shouldBe 1
                val operationsBefore = rig.hardcover.operations.toList()

                val frame =
                    coroutineScope {
                        val next = async(start = CoroutineStart.UNDISPATCHED) { rig.bus.subscribeControl().first() }
                        keepOff() shouldBe AppResult.Success(Unit)
                        next.await()
                    }

                frame shouldBe ControlFrame(SyncControl.ActiveSessionsChanged, ChangeBus.BROADCAST)
                rig.store.pulledReads(USER) shouldBe emptyList()
                rig.hardcover.operations shouldBe operationsBefore
            }
        }

        test("a To Read entry Hardcover added comes off; a book shelved by hand stays") {
            keepOffTest {
                val starter = rig.shelves.createStarterShelf(USER).shouldSucceed().id
                rig.connect()
                rig.hardcover.seedShelf(HC_BOOK, HardcoverStatus.WANT_TO_READ, editionId = 9_001L)
                rig.pullAll()
                rig.shelfBooks.listByShelf(starter).map { it.bookId } shouldBe listOf(BOOK)
                rig.sql.seedTestBook("book-2")
                rig.shelfBooks.addBook(starter, "book-2", USER).shouldSucceed()

                keepOff() shouldBe AppResult.Success(Unit)
                keepOff("book-2") shouldBe AppResult.Success(Unit)

                rig.shelfBooks.listByShelf(starter).map { it.bookId } shouldBe listOf("book-2")
                rig.shelfEntries.recordFor(USER, BOOK) shouldBe null
            }
        }

        test("a To Read entry the listener took off by hand keeps its record") {
            keepOffTest {
                val starter = rig.shelves.createStarterShelf(USER).shouldSucceed().id
                rig.connect()
                rig.hardcover.seedShelf(HC_BOOK, HardcoverStatus.WANT_TO_READ, editionId = 9_001L)
                rig.pullAll()
                rig.shelfEntries.markRemovedByHand(USER, starter, BOOK)
                rig.shelfBooks.removeBook(starter, BOOK, USER).shouldSucceed()

                keepOff() shouldBe AppResult.Success(Unit)

                rig.shelfEntries.recordFor(USER, BOOK)!!.state shouldBe HardcoverShelfEntryState.USER_REMOVED
            }
        }

        test("the listener's Connected is republished, and a send of earlier books it emptied settles") {
            keepOffTest {
                rig.connect()
                link()
                rig.sql.seedOwnRead(USER, BOOK, "r1", finishedAt = T0 - DAY)
                HardcoverHistorySender(rig.sql, rig.clock).send(USER) shouldBe AppResult.Success(Unit)

                val changed =
                    coroutineScope {
                        val next = async(start = CoroutineStart.UNDISPATCHED) { activity.changes.first() }
                        keepOff() shouldBe AppResult.Success(Unit)
                        next.await()
                    }

                changed shouldBe USER
                rig.outbox.pendingFor(USER) shouldBe emptyList()
                rig.sql.hardcoverHistoryQueries.selectHistory(USER).executeAsOne().state shouldBe "DONE"
            }
        }

        test("keeping it off twice keeps the first time; a book the listener can't see is not found") {
            keepOffTest {
                keepOff() shouldBe AppResult.Success(Unit)
                rig.clock.instant = Instant.fromEpochMilliseconds(T0 + DAY)
                keepOff() shouldBe AppResult.Success(Unit)
                rig.sql.hardcoverBookExclusionsQueries.selectExclusion(USER, BOOK).executeAsOne() shouldBe T0

                rig.sql.seedTestBook("book-2")
                keepOff
                    .setSynced(USER, UserRole.MEMBER, "book-2", synced = false)
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<BookError.NotFound>()
                exclusions.isExcluded(USER, "book-2") shouldBe false
            }
        }

        test("it belongs to the listener: no connection is needed, and a disconnect or another account keeps it") {
            keepOffTest {
                keepOff() shouldBe AppResult.Success(Unit)
                exclusions.isExcluded(USER, BOOK) shouldBe true

                rig.connect()
                rig.connections.delete(USER)
                exclusions.isExcluded(USER, BOOK) shouldBe true
                rig.connect(hcUserId = 43)
                exclusions.isExcluded(USER, BOOK) shouldBe true
            }
        }

        test("the list is the listener's kept-off books they can see, by title") {
            keepOffTest {
                rig.sql.seedTestBook("book-2")
                rig.sql.seedTestBook("book-3")
                rig.dbs.driver.execute(null, "UPDATE books SET title = 'Zeta' WHERE id = 'book-1'", 0)
                rig.dbs.driver.execute(null, "UPDATE books SET title = 'alpha' WHERE id = 'book-2'", 0)
                keepOff() shouldBe AppResult.Success(Unit)
                keepOff("book-2") shouldBe AppResult.Success(Unit)

                keepOff.keptOffBooks(USER, UserRole.ROOT) shouldBe listOf("book-2", "book-1")
                keepOff.keptOffBooks(USER, UserRole.MEMBER) shouldBe emptyList()
            }
        }
    })
