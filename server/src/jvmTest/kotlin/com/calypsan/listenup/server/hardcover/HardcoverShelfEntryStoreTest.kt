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

private class ShelfEntryRig(
    dbs: SqlTestDatabases,
) {
    val sql = dbs.sql
    val clock = MutableClock(Instant.fromEpochMilliseconds(1_000L))
    private val bus = ChangeBus()
    private val registry = SyncRegistry()
    val shelves = ShelfRepository(sql, bus, registry, clock)
    val shelfBooks = ShelfBookRepository(sql, bus, registry, clock)
    val store = HardcoverShelfEntryStore(sql, clock)

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook("b1")
        sql.seedTestBook("b2")
    }
}

private fun shelfEntryTest(block: suspend ShelfEntryRig.() -> Unit) =
    withSqlDatabase {
        runTest { ShelfEntryRig(this@withSqlDatabase).block() }
    }

/** The provenance of Hardcover's Want to Read shelf books (#1539), and the user's two target shelves. */
class HardcoverShelfEntryStoreTest :
    FunSpec({

        test("a hand removal marks only Hardcover's record for that shelf and book") {
            shelfEntryTest {
                val toRead = shelves.createPublicShelf(USER, "To Read").shouldSucceed().id
                val other = shelves.createPublicShelf(USER, "Favourites").shouldSucceed().id
                store.putOnShelf(USER, "b1", toRead, hcUserBookId = 7L, seenAt = 1L)

                store.markRemovedByHand(USER, other, "b1")
                store.recordFor(USER, "b1")!!.state shouldBe HardcoverShelfEntryState.ON_SHELF

                store.markRemovedByHand(USER, toRead, "b1")
                store.recordFor(USER, "b1") shouldBe
                    HardcoverShelfEntryRecord("b1", toRead, 7L, HardcoverShelfEntryState.USER_REMOVED)
            }
        }

        test("a sighting reads the record and whether the book is on the target, together") {
            shelfEntryTest {
                val toRead = shelves.createPublicShelf(USER, "To Read").shouldSucceed().id
                shelfBooks.addBook(toRead, "b2", USER).shouldSucceed()
                store.putOnShelf(USER, "b1", toRead, hcUserBookId = 7L, seenAt = 1L)

                store.sighting(USER, "b1", toRead) shouldBe
                    WantToReadSighting(HardcoverShelfEntryRecord("b1", toRead, 7L, HardcoverShelfEntryState.ON_SHELF), isOnTarget = false)
                store.sighting(USER, "b2", toRead) shouldBe WantToReadSighting(record = null, isOnTarget = true)
                store.sighting(USER, "b2", targetShelfId = null) shouldBe WantToReadSighting(record = null, isOnTarget = false)
            }
        }

        test("the sweep's list is every record its full pull didn't see on Want to Read") {
            shelfEntryTest {
                val toRead = shelves.createPublicShelf(USER, "To Read").shouldSucceed().id
                store.putOnShelf(USER, "b1", toRead, hcUserBookId = 7L, seenAt = 100L)
                store.putOnShelf(USER, "b2", toRead, hcUserBookId = 8L, seenAt = 100L)
                store.markSeen(USER, "b2", hcUserBookId = 8L, seenAt = 300L)

                store.notSeenSince(USER, since = 200L).map { it.bookId } shouldBe listOf("b1")
                store.recordsFromEntries(USER, listOf(8L, 99L)).map { it.bookId } shouldBe listOf("b2")
                store.recordsFromEntries(USER, emptyList()) shouldBe emptyList()
            }
        }

        test("the targets are the remembered starter shelf and Hardcover's own, and forgetting drops every record") {
            shelfEntryTest {
                val starter = shelves.createStarterShelf(USER).shouldSucceed().id
                store.targets(USER) shouldBe WantToReadTargets(starterShelfId = starter, hardcoverShelfId = null)
                store.rememberHardcoverShelf(USER, "s-hc")
                store.targets(USER) shouldBe WantToReadTargets(starterShelfId = starter, hardcoverShelfId = "s-hc")

                store.putOnShelf(USER, "b1", starter, hcUserBookId = 7L, seenAt = 1L)
                store.forgetAll(USER)
                store.records(USER) shouldBe emptyList()
            }
        }
    })
