package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.ShelfReadAssembler
import com.calypsan.listenup.server.api.ShelfServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val HC_BOOK = 427_578L
private const val BOOK_2 = "book-2"
private const val HC_BOOK_2 = 1_000L

/** The rig user's starter "To Read" shelf, made the way registration makes it. */
private suspend fun PullRig.starterShelf(): String =
    shelves
        .createStarterShelf(USER)
        .shouldBeInstanceOf<AppResult.Success<ShelfSyncPayload>>()
        .data.id

/** The books on [shelfId], in shelf order. */
private suspend fun PullRig.booksOn(shelfId: String): List<String> = shelfBooks.listByShelf(shelfId).map { it.bookId }

/** Puts [hcBookId] on the user's Hardcover Want to Read list, logged as [editionId]. */
private fun PullRig.wantToReadOnHardcover(
    hcBookId: Long = HC_BOOK,
    editionId: Long = 9_001L,
) {
    hardcover.seedShelf(hcBookId, HardcoverStatus.WANT_TO_READ, editionId = editionId)
}

/** A second library book, *Project Hail Mary*, and its Hardcover edition 9_002. */
private fun PullRig.secondBook() {
    sql.seedTestBook(BOOK_2, asin = "B08G9RZBTT")
    hardcover.addEdition(
        FakeHardcoverLibrary.Edition(
            id = 9_002L,
            bookId = HC_BOOK_2,
            title = "Project Hail Mary",
            authors = listOf("Andy Weir"),
            asin = "B08G9RZBTT",
            readingFormatId = 2,
            defaultAudioEditionId = 9_002L,
        ),
    )
}

/** Every sync event the [domain] domain ("shelves" or "shelf_books") published, oldest first. */
private fun PullRig.syncEvents(domain: String): List<SyncEvent<*>> =
    bus
        .subscribe()
        .replayCache
        .filter { it.repo.domainName == domain }
        .map { it.event }

/** Moves the rig's clock past the full-pull interval, so the next pull re-reads the whole shelf. */
private fun PullRig.aDayLater() {
    clock.instant =
        Instant.fromEpochMilliseconds(
            clock.now().toEpochMilliseconds() + FULL_PULL_INTERVAL.inWholeMilliseconds + 1.minutes.inWholeMilliseconds,
        )
}

/** The user takes [bookId] off [shelfId] by hand, through the real shelf RPC implementation. */
private suspend fun PullRig.removeByHand(
    shelfId: String,
    bookId: String,
) {
    ShelfServiceImpl(
        shelfRepo = shelves,
        shelfBookRepo = shelfBooks,
        bookAccessPolicy = BookAccessPolicy(sql, dbs.driver),
        readAssembler = ShelfReadAssembler(sql),
        clock = clock,
        principal = PrincipalProvider { UserPrincipal(UserId(USER), SessionId("s-$USER"), UserRole.ROOT) },
        hardcoverShelfEntries = shelfEntries,
    ).removeBookFromShelf(ShelfId(shelfId), BookId(bookId)).shouldBeInstanceOf<AppResult.Success<Unit>>()
}

/** #1539: Hardcover's Want to Read list, on the user's To Read shelf — one way, and only what Hardcover added. */
class HardcoverWantToReadTest :
    FunSpec({

        test("a Want to Read book lands on the starter To Read shelf, and Hardcover is recorded as having put it there") {
            pullTest {
                val starter = starterShelf()
                connect()
                wantToReadOnHardcover()

                pullAll()

                booksOn(starter) shouldBe listOf(BOOK)
                shelfEntries.recordFor(USER, BOOK)!!.let {
                    it.shelfId shouldBe starter
                    it.state shouldBe HardcoverShelfEntryState.ON_SHELF
                }
                links.linkFor(USER, BOOK)!!.method shouldBe HardcoverMatchMethod.ASIN
            }
        }

        test("the starter shelf is still where Want to Read lands after the user renames it") {
            pullTest {
                val starter = starterShelf()
                shelves.upsert(shelves.findById(starter)!!.copy(name = "Someday"), userId = USER)
                connect()
                wantToReadOnHardcover()

                pullAll()

                booksOn(starter) shouldBe listOf(BOOK)
                shelves.listOwnedBy(USER).map { it.name } shouldBe listOf("Someday")
            }
        }

        test("with the starter shelf deleted, the first book makes a public Want to Read shelf, and later books reuse it") {
            pullTest {
                val starter = starterShelf()
                shelves.softDelete(ShelfId(starter), userId = USER)
                secondBook()
                connect()
                wantToReadOnHardcover()

                pullAll()

                val made = shelves.listOwnedBy(USER).single()
                made.name shouldBe "Want to Read"
                made.isPrivate shouldBe false
                booksOn(made.id) shouldBe listOf(BOOK)
                shelfEntries.targets(USER)!!.hardcoverShelfId shouldBe made.id

                wantToReadOnHardcover(HC_BOOK_2, editionId = 9_002L)
                pullAll()

                shelves.listOwnedBy(USER).map { it.id } shouldBe listOf(made.id)
                booksOn(made.id) shouldBe listOf(BOOK, BOOK_2)
            }
        }

        test("with no Want to Read book to place, no shelf is made") {
            pullTest {
                shelves.softDelete(ShelfId(starterShelf()), userId = USER)
                connect()
                hardcover.seedShelf(HC_BOOK, HardcoverStatus.READ, "2017-01-02" to "2017-03-01", editionId = 9_001L)

                pullAll()

                shelves.listOwnedBy(USER) shouldBe emptyList()
                shelfEntries.records(USER) shouldBe emptyList()
            }
        }

        test("a Want to Read book the library doesn't have is skipped") {
            pullTest {
                shelves.softDelete(ShelfId(starterShelf()), userId = USER)
                hardcover.addEdition(
                    FakeHardcoverLibrary.Edition(id = 9_005L, bookId = 5_555L, title = "A Book Nobody Owns", authors = listOf("Nobody")),
                )
                connect()
                wantToReadOnHardcover(5_555L, editionId = 9_005L)

                pullAll()

                shelves.listOwnedBy(USER) shouldBe emptyList()
                shelfEntries.records(USER) shouldBe emptyList()
            }
        }

        test("a book the user can no longer see is never shelved, even though it is linked") {
            pullTest {
                val starter = starterShelf()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(HC_BOOK, 9_001L, HardcoverMatchMethod.ASIN))
                dbs.driver.execute(null, "UPDATE books SET deleted_at = 1 WHERE id = '$BOOK'", 0)
                connect()
                wantToReadOnHardcover()

                pullAll()

                booksOn(starter) shouldBe emptyList()
                shelfEntries.records(USER) shouldBe emptyList()
            }
        }

        test("pulling twice changes nothing, incrementally or in full") {
            pullTest {
                val starter = starterShelf()
                connect()
                wantToReadOnHardcover()
                pullAll()
                val events = syncEvents("shelf_books").size

                pullAll()
                store.requestFullPull(USER)
                pullAll()

                booksOn(starter) shouldBe listOf(BOOK)
                syncEvents("shelf_books").size shouldBe events
            }
        }

        test("what Hardcover shelves reaches every device through the shelves sync domains") {
            pullTest {
                shelves.softDelete(ShelfId(starterShelf()), userId = USER)
                connect()
                wantToReadOnHardcover()

                pullAll()

                val made = shelves.listOwnedBy(USER).single()
                syncEvents("shelves").filterIsInstance<SyncEvent.Created<*>>().size shouldBe 2 // the starter, then Want to Read
                syncEvents("shelf_books").filterIsInstance<SyncEvent.Created<*>>().size shouldBe 1
                shelfBooks
                    .pullSince(userId = USER, cursor = 0L, limit = 50)
                    .items
                    .map { it.shelfId to it.bookId } shouldBe listOf(made.id to BOOK)
            }
        }

        test("a book already on the shelf by hand is never recorded, and never taken off") {
            pullTest {
                val starter = starterShelf()
                shelfBooks.addBook(starter, BOOK, USER)
                connect()
                wantToReadOnHardcover()
                pullAll()
                shelfEntries.recordFor(USER, BOOK) shouldBe null

                hardcover.moveTo(HC_BOOK, HardcoverStatus.READ)
                pullAll()

                booksOn(starter) shouldBe listOf(BOOK)
            }
        }

        test("starting or finishing it on Hardcover takes off only the books Hardcover added") {
            pullTest {
                val starter = starterShelf()
                secondBook()
                shelfBooks.addBook(starter, BOOK_2, USER)
                connect()
                wantToReadOnHardcover()
                wantToReadOnHardcover(HC_BOOK_2, editionId = 9_002L)
                pullAll()
                booksOn(starter) shouldBe listOf(BOOK_2, BOOK)

                hardcover.moveTo(HC_BOOK, HardcoverStatus.READING)
                hardcover.moveTo(HC_BOOK_2, HardcoverStatus.READ)
                pullAll()

                booksOn(starter) shouldBe listOf(BOOK_2)
                shelfEntries.records(USER) shouldBe emptyList()
                syncEvents("shelf_books").filterIsInstance<SyncEvent.Deleted>().size shouldBe 1
            }
        }

        test("an entry deleted on Hardcover leaves the shelf at the daily full pull, and only then") {
            pullTest {
                val starter = starterShelf()
                connect()
                wantToReadOnHardcover()
                pullAll()

                hardcover.deleteShelf(HC_BOOK)
                pullAll()
                booksOn(starter) shouldBe listOf(BOOK)

                aDayLater()
                pullAll()

                booksOn(starter) shouldBe emptyList()
                shelfEntries.records(USER) shouldBe emptyList()
            }
        }

        test("a book taken off by hand stays off while it stays on Want to Read, and comes back if it leaves and returns") {
            pullTest {
                val starter = starterShelf()
                connect()
                wantToReadOnHardcover()
                pullAll()

                removeByHand(starter, BOOK)
                store.requestFullPull(USER)
                pullAll()
                booksOn(starter) shouldBe emptyList()
                shelfEntries.recordFor(USER, BOOK)!!.state shouldBe HardcoverShelfEntryState.USER_REMOVED

                hardcover.moveTo(HC_BOOK, HardcoverStatus.READ)
                pullAll()
                shelfEntries.recordFor(USER, BOOK) shouldBe null

                hardcover.moveTo(HC_BOOK, HardcoverStatus.WANT_TO_READ)
                pullAll()
                booksOn(starter) shouldBe listOf(BOOK)
            }
        }

        test("disconnecting takes off what Hardcover shelved, keeps what the user shelved, and forgets every record") {
            pullTest {
                val starter = starterShelf()
                secondBook()
                shelfBooks.addBook(starter, BOOK_2, USER)
                connect()
                wantToReadOnHardcover()
                wantToReadOnHardcover(HC_BOOK_2, editionId = 9_002L)
                pullAll()
                booksOn(starter) shouldBe listOf(BOOK_2, BOOK)

                connections.delete(USER)

                booksOn(starter) shouldBe listOf(BOOK_2)
                shelfEntries.records(USER) shouldBe emptyList()
            }
        }

        test("reconnecting the same account keeps Hardcover's books; connecting a different one takes them off") {
            pullTest {
                val starter = starterShelf()
                connect(hcUserId = 42)
                wantToReadOnHardcover()
                pullAll()

                connect(hcUserId = 42)
                booksOn(starter) shouldBe listOf(BOOK)
                shelfEntries.recordFor(USER, BOOK)!!.state shouldBe HardcoverShelfEntryState.ON_SHELF

                connect(hcUserId = 99)
                booksOn(starter) shouldBe emptyList()
                shelfEntries.records(USER) shouldBe emptyList()
            }
        }
    })
