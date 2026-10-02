package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

private const val USER = "u1"
private const val OTHER = "u2"
private const val T0 = 1_779_451_200_000L

private class ExclusionsRig(
    dbs: SqlTestDatabases,
) {
    val sql = dbs.sql
    val driver = dbs.driver
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    private val bus = ChangeBus()
    private val registry = SyncRegistry()
    val shelves = ShelfRepository(sql, bus, registry, clock)
    val shelfBooks = ShelfBookRepository(sql, bus, registry, clock)
    val shelfEntries = HardcoverShelfEntryStore(sql, clock)
    val exclusions = HardcoverExclusions(sql)

    init {
        sql.seedTestUser(USER)
        sql.seedTestUser(OTHER)
        sql.seedTestLibraryAndFolder()
        listOf("book-1", "book-2").forEach { sql.seedTestBook(it) }
    }
}

private fun exclusionsTest(block: suspend ExclusionsRig.() -> Unit) =
    withSqlDatabase { runTest { ExclusionsRig(this@withSqlDatabase).block() } }

/** #1541: which books a listener keeps off Hardcover, and what keeping one off would take out of ListenUp. */
class HardcoverExclusionsTest :
    FunSpec({

        test("a kept-off book is kept off for that listener only") {
            exclusionsTest {
                sql.seedExclusion(USER, "book-1", at = T0)

                exclusions.isExcluded(USER, "book-1") shouldBe true
                exclusions.isExcluded(USER, "book-2") shouldBe false
                exclusions.isExcluded(OTHER, "book-1") shouldBe false
            }
        }

        test("the count is of the listener's kept-off books still in the library") {
            exclusionsTest {
                sql.seedExclusion(USER, "book-1", at = T0)
                sql.seedExclusion(USER, "book-2", at = T0)
                sql.seedExclusion(OTHER, "book-1", at = T0)
                sql.keptOffBookCount(USER) shouldBe 2

                // Soft-removed, as a library removal leaves it.
                driver.execute(null, "UPDATE books SET deleted_at = $T0 WHERE id = 'book-2'", 0)
                sql.keptOffBookCount(USER) shouldBe 1
            }
        }

        test("keeping a book off would remove its Hardcover reads from Readers, when it has any") {
            exclusionsTest {
                exclusions.keepOffRemovals(USER, "book-1") shouldBe HardcoverKeepOffRemovals(false, false)
                sql.seedOwnRead(USER, "book-1", "own", finishedAt = T0)
                exclusions.keepOffRemovals(USER, "book-1").readsInReaders shouldBe false
                sql.seedPulledRead(USER, "book-1", hcReadId = 7L, finishedAt = T0)
                exclusions.keepOffRemovals(USER, "book-1").readsInReaders shouldBe true
                exclusions.keepOffRemovals(OTHER, "book-1").readsInReaders shouldBe false
            }
        }

        test("and the book from To Read, only while Hardcover put it there and it is still on that shelf") {
            exclusionsTest {
                val toRead = shelves.createPublicShelf(USER, "To Read").shouldSucceed().id
                shelfEntries.putOnShelf(USER, "book-1", toRead, hcUserBookId = 70L, seenAt = T0)
                exclusions.keepOffRemovals(USER, "book-1").onToReadFromHardcover shouldBe false // recorded, not yet added

                shelfBooks.addBook(toRead, "book-1", USER).shouldSucceed()
                exclusions.keepOffRemovals(USER, "book-1").onToReadFromHardcover shouldBe true

                shelfEntries.markRemovedByHand(USER, toRead, "book-1")
                exclusions.keepOffRemovals(USER, "book-1").onToReadFromHardcover shouldBe false

                // On the shelf by hand: no record, so it is never Hardcover's to take off.
                shelfBooks.addBook(toRead, "book-2", USER).shouldSucceed()
                exclusions.keepOffRemovals(USER, "book-2").onToReadFromHardcover shouldBe false
            }
        }
    })
