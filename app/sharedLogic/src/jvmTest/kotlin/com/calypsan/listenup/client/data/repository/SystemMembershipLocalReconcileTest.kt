package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.ALL_BOOKS
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.INBOX
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.applyReleaseEcho
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.hold
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.membership
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.seedBook
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.awaitItemMatching
import com.calypsan.listenup.client.data.local.db.withHeldBookDb
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.fake.FakeUserRepository
import com.calypsan.listenup.client.test.normalCollection
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first

/**
 * The client's mirror of the server's system-membership reconcile, over a real in-memory Room.
 *
 * A book is in its library's All Books exactly while it has no live normal membership and is not
 * held. The server enforces that on every membership write; the client must too, or the two
 * ordinary ways a book leaves its last restriction — Release from the inbox, and removing its last
 * collection — read as *Stranded, hidden from all members* until the echo lands (and for the whole
 * of an offline spell), with a live "Show to all members" button on a book that is about to be
 * public anyway.
 */
class SystemMembershipLocalReconcileTest :
    FunSpec({
        test("a release reads Public at once — never Stranded — before any echo, and the echo keeps it Public") {
            withHeldBookDb { db ->
                seedBook(db, "b1")
                hold(db, "b1")
                val visibility = visibilityOf(db)

                visibility.observeBookVisibility(BookId("b1")).test {
                    awaitItemMatching { it == BookVisibility.Held }

                    inbox(db).releaseBooks("lib1", mapOf("b1" to emptyList())).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    awaitItemMatching { it != BookVisibility.Held } shouldBe BookVisibility.Public
                    cancelAndIgnoreRemainingEvents()
                }

                applyReleaseEcho(db, "b1")
                visibility.observeBookVisibility(BookId("b1")).first() shouldBe BookVisibility.Public
                db.collectionBookDao().findByKey(ALL_BOOKS, "b1").shouldNotBeNull().deletedAt.shouldBeNull()
            }
        }

        test("removing a book's last normal collection reads Public at once") {
            withHeldBookDb { db ->
                seedBook(db, "b1")
                db.collectionDao().upsert(normalCollection("c1", "Kids"))
                db.collectionBookDao().upsert(membership("c1", "b1"))

                bookEdit(db).setBookCollections(BookId("b1"), emptyList()).shouldBeInstanceOf<AppResult.Success<Unit>>()

                visibilityOf(db).observeBookVisibility(BookId("b1")).first() shouldBe BookVisibility.Public
            }
        }

        test("a revived All Books row outranks the stale tombstone it replaced, and the echo still converges it") {
            withHeldBookDb { db ->
                seedBook(db, "b1")
                db.collectionDao().upsert(normalCollection("c1", "Kids"))
                db.collectionBookDao().upsert(membership("c1", "b1"))
                // The server dropped the book out of All Books when it was curated: that tombstone,
                // at revision 3, is what the device last saw.
                db.collectionBookDao().upsert(membership(ALL_BOOKS, "b1").copy(revision = 3L, deletedAt = 50L))
                val handler = collectionBooksDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                val visibility = visibilityOf(db)

                bookEdit(db).setBookCollections(BookId("b1"), emptyList())
                visibility.observeBookVisibility(BookId("b1")).first() shouldBe BookVisibility.Public

                // A replayed frame of that old tombstone must not hide the book again.
                handler.onEvent(SyncEvent.Deleted(id = "$ALL_BOOKS:b1", revision = 3L, occurredAt = 60L))
                visibility.observeBookVisibility(BookId("b1")).first() shouldBe BookVisibility.Public

                // The server's re-home arrives at a later revision, under whatever wire id it minted.
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "server-all-books-b1",
                        revision = 9L,
                        occurredAt = 70L,
                        clientOpId = null,
                        payload =
                            CollectionBookSyncPayload(
                                id = "server-all-books-b1",
                                collectionId = ALL_BOOKS,
                                bookId = "b1",
                                createdAt = 70L,
                                revision = 9L,
                                deletedAt = null,
                            ),
                    ),
                )
                val row = db.collectionBookDao().findByKey(ALL_BOOKS, "b1").shouldNotBeNull()
                row.deletedAt.shouldBeNull()
                row.revision shouldBe 9L
                row.syncId shouldBe "server-all-books-b1"
                visibility.observeBookVisibility(BookId("b1")).first() shouldBe BookVisibility.Public
            }
        }

        test("a book whose library's All Books has not synced stays Stranded — the honest fallback") {
            withHeldBookDb(seedSystemCollections = false) { db ->
                seedBook(db, "b1")
                // Another library's All Books is synced; it is not this book's, so it must not be used.
                db.collectionDao().upsert(
                    CollectionEntity(
                        id = "lib2-all-books",
                        libraryId = "lib2",
                        ownerId = "root",
                        name = "All Books",
                        isInbox = false,
                        isSystem = true,
                        revision = 1L,
                        updatedAt = 1L,
                    ),
                )
                db.collectionDao().upsert(normalCollection("c1", "Kids"))
                db.collectionBookDao().upsert(membership("c1", "b1"))

                bookEdit(db).setBookCollections(BookId("b1"), emptyList())

                visibilityOf(db).observeBookVisibility(BookId("b1")).first() shouldBe BookVisibility.Stranded
                db.collectionBookDao().findByKey("lib2-all-books", "b1").shouldBeNull()
            }
        }

        test("a held book given an empty collection set stays held, and is not put in All Books") {
            withHeldBookDb { db ->
                seedBook(db, "b1")
                hold(db, "b1")

                bookEdit(db).setBookCollections(BookId("b1"), emptyList())

                visibilityOf(db).observeBookVisibility(BookId("b1")).first() shouldBe BookVisibility.Held
                db.collectionBookDao().findByKey(ALL_BOOKS, "b1").shouldBeNull()
            }
        }

        test("a book still in another normal collection is not put in All Books") {
            withHeldBookDb { db ->
                seedBook(db, "b1")
                db.collectionDao().upsert(normalCollection("c1", "Kids"))
                db.collectionDao().upsert(normalCollection("c2", "Teens"))
                db.collectionBookDao().upsert(membership("c1", "b1"))
                db.collectionBookDao().upsert(membership("c2", "b1"))

                bookEdit(db).setBookCollections(BookId("b1"), listOf("c2"))

                db.collectionBookDao().findByKey(ALL_BOOKS, "b1").shouldBeNull()
                visibilityOf(db).observeBookVisibility(BookId("b1")).first().shouldBeInstanceOf<BookVisibility.Restricted>()
            }
        }

        test("curating a public book takes it out of All Books, and curating a held book releases it") {
            withHeldBookDb { db ->
                seedBook(db, "public")
                seedBook(db, "held")
                db.collectionDao().upsert(normalCollection("c1", "Kids"))
                db.collectionBookDao().upsert(membership(ALL_BOOKS, "public"))
                hold(db, "held")

                bookEdit(db).setBookCollections(BookId("public"), listOf("c1"))
                bookEdit(db).setBookCollections(BookId("held"), listOf("c1"))

                db.collectionBookDao().findByKey(ALL_BOOKS, "public").shouldNotBeNull().deletedAt shouldNotBe null
                db.collectionBookDao().findByKey(INBOX, "held").shouldNotBeNull().deletedAt shouldNotBe null
                db.collectionBookDao().findByKey(ALL_BOOKS, "held").shouldBeNull()
                db.collectionBookDao().observeCollectionIdsForBook("held").first() shouldBe listOf("c1")
            }
        }

        test("a release into collections does not put the book in All Books") {
            withHeldBookDb { db ->
                seedBook(db, "b1")
                db.collectionDao().upsert(normalCollection("c1", "Kids"))
                hold(db, "b1")

                inbox(db).releaseBooks("lib1", mapOf("b1" to listOf("c1")))

                db.collectionBookDao().findByKey(ALL_BOOKS, "b1").shouldBeNull()
            }
        }
    })

private fun visibilityOf(db: ListenUpDatabase) =
    BookVisibilityRepositoryImpl(
        collectionDao = db.collectionDao(),
        collectionBookDao = db.collectionBookDao(),
        collectionShareDao = db.collectionShareDao(),
        adminUserRosterDao = db.adminUserRosterDao(),
        userRepository = FakeUserRepository(initialIsAdmin = true),
    )

private fun inbox(db: ListenUpDatabase): InboxRepositoryImpl {
    val service = mock<CollectionService>()
    everySuspend { service.releaseBooks(any(), any()) } returns AppResult.Success(Unit)
    return InboxRepositoryImpl(
        channel = RpcChannel.forTest(service),
        scannerChannel = RpcChannel.forTest(mock<ScannerService>()),
        collectionBookDao = db.collectionBookDao(),
        transactionRunner = RoomTransactionRunner(db),
    )
}

/** Book edits against a queue whose op never drains — the device is, in effect, offline. */
private fun bookEdit(db: ListenUpDatabase): BookEditRepository =
    BookEditRepositoryImpl(
        offlineEditor =
            OfflineEditor(
                pendingQueue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    ),
                transactionRunner = RoomTransactionRunner(db),
                authSession = FakeAuthSession(userId = "u1"),
            ),
        localApply =
            BookMutationLocalApply(
                bookDao = db.bookDao(),
                bookContributorDao = db.bookContributorDao(),
                contributorDao = db.contributorDao(),
                bookSeriesDao = db.bookSeriesDao(),
                seriesDao = db.seriesDao(),
                genreDao = db.genreDao(),
                chapterDao = db.chapterDao(),
                collectionBookDao = db.collectionBookDao(),
            ),
        bookDao = db.bookDao(),
    )
