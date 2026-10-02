package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.testing.CollectionAccessHarness
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.FaultInjectingCollectionBookRepository
import com.calypsan.listenup.server.testing.actAs
import com.calypsan.listenup.server.testing.collectionAccessHarness
import com.calypsan.listenup.server.testing.grantAllBooks
import com.calypsan.listenup.server.testing.junctionDiagnostic
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for #1226: [CollectionServiceImpl] used to discard the [AppResult] returned
 * by [com.calypsan.listenup.server.sync.CollectionBookRepository.upsert] in three places
 * ([CollectionServiceImpl.setBookCollections], [CollectionServiceImpl.releaseBooks],
 * `reconcileSystemMembership`), so a failed membership write was silently swallowed.
 *
 * The dangerous interleaving: an admin releases a held book into a *private* collection whose
 * membership write fails (a DB fault) — the inbox tombstone still commits, so the end-of-release
 * reconcile reads back zero real memberships and re-homes the book into the everyone-visible
 * `ALL_BOOKS` substrate, silently widening a private book to every member while the call still
 * reported [AppResult.Success]. Now a book whose release fails stays held instead — still in
 * the inbox, in no other collection — and the call answers [CollectionError.ReleaseIncomplete]. These tests drive the real [CollectionServiceImpl] over a real
 * Flyway-migrated in-memory SQLite db, with [FaultInjectingCollectionBookRepository] standing in
 * for the real [com.calypsan.listenup.server.sync.CollectionBookRepository] to make one chosen
 * `(collectionId, bookId)` write fail exactly like a real fault would — no row is written.
 */
class CollectionMembershipWriteFailureTest :
    FunSpec({

        test("releaseBooks: a failed private-collection write never re-homes the book to ALL_BOOKS") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("member")
                sql.seedTestBook("book1")
                runTest {
                    // Set up the private target collection and a pre-existing ALL_BOOKS with a
                    // granted member, using the harness's real (non-faulty) repo.
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val privateCollection = setupAdmin.createCollection("test-library", "Private")
                    require(privateCollection is AppResult.Success)
                    val privateId = privateCollection.data.id.value

                    val allBooks = setupAdmin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    setup.grantAllBooks(allBooksId, "member")

                    setupAdmin.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    // Now build the harness whose collectionBookRepo fails exactly the
                    // (privateId, book1) write — the release's target-collection upsert.
                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                throwingPairs = setOf(privateId to "book1"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    // The failure is reported, typed, naming the book that stayed held — never a
                    // Success the caller would announce as "Released 1 book".
                    val result =
                        admin.releaseBooks(
                            LibraryId("test-library"),
                            mapOf(BookId("book1") to listOf(CollectionId(privateId))),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error
                        .shouldBeInstanceOf<CollectionError.ReleaseIncomplete>()
                        .failedBookIds shouldBe listOf("book1")

                    // The dangerous outcome this test guards: the book must NOT have been re-homed
                    // into ALL_BOOKS (which would make it visible to every member) despite its
                    // intended private-collection write failing.
                    h.bookAccessPolicy.canAccess("member", UserRole.MEMBER, "book1").shouldBeFalse()
                    // And it is still held: back in the inbox, retryable, not stranded in no collection.
                    h.junctionDiagnostic("book1") shouldBe setOf(inboxIdOf(admin))
                    admin.listInbox(LibraryId("test-library")) shouldBe AppResult.Success(listOf(BookId("book1")))
                    // Nothing about the held book changed, so nothing is announced for it.
                    h.revisionTouch.touched shouldNotContain "book1"
                }
            }
        }

        test("releaseBooks: the books that can be released are, and only the failed one stays held") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("member")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val allBooks = setupAdmin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    setup.grantAllBooks(allBooksId, "member")
                    setupAdmin.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)
                    setupAdmin.addToInbox("book2", "test-library") shouldBe AppResult.Success(Unit)

                    // A public release of both, where book1's ALL_BOOKS write fails.
                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                throwingPairs = setOf(allBooksId to "book1"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val result =
                        admin.releaseBooks(
                            LibraryId("test-library"),
                            mapOf(BookId("book1") to emptyList(), BookId("book2") to emptyList()),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error
                        .shouldBeInstanceOf<CollectionError.ReleaseIncomplete>()
                        .failedBookIds shouldBe listOf("book1")

                    // book2 released normally: public, out of the inbox, and announced.
                    h.junctionDiagnostic("book2") shouldBe setOf(allBooksId)
                    h.bookAccessPolicy.canAccess("member", UserRole.MEMBER, "book2").shouldBeTrue()
                    h.revisionTouch.touched shouldContain "book2"

                    // book1 stayed held — in the inbox and nowhere else.
                    h.junctionDiagnostic("book1") shouldBe setOf(inboxIdOf(admin))
                    h.bookAccessPolicy.canAccess("member", UserRole.MEMBER, "book1").shouldBeFalse()
                    h.revisionTouch.touched shouldNotContain "book1"
                    admin.listInbox(LibraryId("test-library")) shouldBe AppResult.Success(listOf(BookId("book1")))
                }
            }
        }

        test("releaseBooks: a book whose second target fails is withdrawn from its first, so held means hidden") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("book1")
                runTest {
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val okCollection = setupAdmin.createCollection("test-library", "OK")
                    val failCollection = setupAdmin.createCollection("test-library", "FAIL")
                    require(okCollection is AppResult.Success)
                    require(failCollection is AppResult.Success)
                    val okId = okCollection.data.id.value
                    val failId = failCollection.data.id.value
                    setupAdmin.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                throwingPairs = setOf(failId to "book1"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val result =
                        admin.releaseBooks(
                            LibraryId("test-library"),
                            mapOf(BookId("book1") to listOf(CollectionId(okId), CollectionId(failId))),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error
                        .shouldBeInstanceOf<CollectionError.ReleaseIncomplete>()
                        .failedBookIds shouldBe listOf("book1")

                    // The OK write landed, but a held book in a members' collection would be visible
                    // while the inbox says it is waiting for review — the release takes it back out.
                    h.junctionDiagnostic("book1") shouldBe setOf(inboxIdOf(admin))
                }
            }
        }

        test("releaseBooks: a throwing write mid-release keeps that book held and still finishes the others") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("member")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                runTest {
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val a = setupAdmin.createCollection("test-library", "A")
                    val fail = setupAdmin.createCollection("test-library", "FAIL")
                    require(a is AppResult.Success)
                    require(fail is AppResult.Success)
                    val aId = a.data.id.value
                    val failId = fail.data.id.value
                    val allBooks = setupAdmin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    setup.grantAllBooks(allBooksId, "member")
                    for (id in listOf("book1", "book2", "book3")) {
                        setupAdmin.addToInbox(id, "test-library") shouldBe AppResult.Success(Unit)
                    }
                    // A stale ALL_BOOKS membership on book1: only the post-release reconcile removes it,
                    // so its absence afterwards proves book1's reconcile ran.
                    setup.collectionBookRepo.upsert(membership(allBooksId, "book1")).shouldBeInstanceOf<AppResult.Success<*>>()

                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                throwingPairs = setOf(failId to "book2"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val result =
                        admin.releaseBooks(
                            LibraryId("test-library"),
                            linkedMapOf(
                                BookId("book1") to listOf(CollectionId(aId)),
                                BookId("book2") to listOf(CollectionId(failId)),
                                BookId("book3") to emptyList(),
                            ),
                        )

                    result.shouldBeReleaseIncomplete(listOf("book2"))
                    // The failing book stayed held: INBOX only, no target row, hidden from members.
                    h.junctionDiagnostic("book2") shouldBe setOf(inboxIdOf(admin))
                    h.bookAccessPolicy.canAccess("member", UserRole.MEMBER, "book2").shouldBeFalse()
                    // The books around it released AND were announced and reconciled.
                    h.junctionDiagnostic("book1") shouldBe setOf(aId)
                    h.junctionDiagnostic("book3") shouldBe setOf(allBooksId)
                    h.revisionTouch.touched shouldContain "book1"
                    h.revisionTouch.touched shouldContain "book3"
                    h.revisionTouch.touched shouldNotContain "book2"
                    admin.listInbox(LibraryId("test-library")) shouldBe AppResult.Success(listOf(BookId("book2")))
                }
            }
        }

        test("releaseBooks: a failed release never removes a membership the book already had") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("book1")
                runTest {
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val a = setupAdmin.createCollection("test-library", "A")
                    val fail = setupAdmin.createCollection("test-library", "FAIL")
                    require(a is AppResult.Success)
                    require(fail is AppResult.Success)
                    val aId = a.data.id.value
                    val failId = fail.data.id.value
                    setupAdmin.addBookToCollection(a.data.id, BookId("book1")) shouldBe AppResult.Success(Unit)
                    setupAdmin.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)
                    val before = setup.junctionDiagnostic("book1")
                    before shouldBe setOf(aId, inboxIdOf(setupAdmin))

                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                throwingPairs = setOf(failId to "book1"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val result =
                        admin.releaseBooks(
                            LibraryId("test-library"),
                            mapOf(BookId("book1") to listOf(CollectionId(aId), CollectionId(failId))),
                        )

                    result.shouldBeReleaseIncomplete(listOf("book1"))
                    h.junctionDiagnostic("book1") shouldBe before
                }
            }
        }

        test("releaseBooks: a book that stayed held nudges no one about it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("s1")
                sql.seedTestUser("s2")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val a = setupAdmin.createCollection("test-library", "A")
                    val fail = setupAdmin.createCollection("test-library", "FAIL")
                    require(a is AppResult.Success)
                    require(fail is AppResult.Success)
                    val aId = a.data.id.value
                    val failId = fail.data.id.value
                    setup.readGrant(aId, "s1")
                    setup.readGrant(failId, "s2")
                    setupAdmin.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)
                    setupAdmin.addToInbox("book2", "test-library") shouldBe AppResult.Success(Unit)

                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                throwingPairs = setOf(failId to "book2"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val recipients = mutableListOf<String>()
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            h.bus.subscribeControl().collect { frame ->
                                if (frame.control is SyncControl.AccessChanged) recipients += frame.userId
                            }
                        }
                    admin.releaseBooks(
                        LibraryId("test-library"),
                        linkedMapOf(
                            BookId("book1") to listOf(CollectionId(aId)),
                            BookId("book2") to listOf(CollectionId(failId)),
                        ),
                    )
                    runCurrent()
                    collector.cancel()

                    // book1's audience hears of its release; book2's audience hears nothing — it changed nothing.
                    recipients shouldContain "s1"
                    recipients shouldNotContain "s2"
                }
            }
        }

        test("setBookCollections logs a failed add but still commits the other collections in the batch") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("book1")
                runTest {
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val okCollection = setupAdmin.createCollection("test-library", "OK")
                    val failCollection = setupAdmin.createCollection("test-library", "FAIL")
                    require(okCollection is AppResult.Success)
                    require(failCollection is AppResult.Success)
                    val okId = okCollection.data.id.value
                    val failId = failCollection.data.id.value

                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                failingPairs = setOf(failId to "book1"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    admin.setBookCollections(
                        BookId("book1"),
                        listOf(CollectionId(okId), CollectionId(failId)),
                    ) shouldBe AppResult.Success(Unit)

                    // The batch continues past the failed write: the other collection's add commits.
                    h.junctionDiagnostic("book1").let {
                        it shouldContain okId
                        it shouldNotContain failId
                    }
                }
            }
        }

        test("reconcileSystemMembership leaves the book's prior state when the ALL_BOOKS write fails") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("member")
                sql.seedTestBook("book1")
                runTest {
                    val setup = collectionAccessHarness()
                    val setupAdmin = setup.service.actAs("admin", UserRole.ADMIN)
                    val c = setupAdmin.createCollection("test-library", "C")
                    require(c is AppResult.Success)

                    val allBooks = setupAdmin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    setup.grantAllBooks(allBooksId, "member")

                    setupAdmin.addBookToCollection(c.data.id, BookId("book1")) shouldBe AppResult.Success(Unit)

                    // The book's only real membership is about to be removed, which triggers
                    // reconcile's "return to ALL_BOOKS" branch — fail exactly that write.
                    val h =
                        collectionAccessHarness { bus, registry ->
                            FaultInjectingCollectionBookRepository(
                                db = sql,
                                bus = bus,
                                registry = registry,
                                driver = driver,
                                failingPairs = setOf(allBooksId to "book1"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    admin.removeBookFromCollection(c.data.id, BookId("book1")) shouldBe AppResult.Success(Unit)

                    // The ALL_BOOKS write failed, so the book stays exactly where it was left by the
                    // removal — orphaned, not re-homed — rather than a reconcile that half-applied.
                    h.junctionDiagnostic("book1").let {
                        it shouldNotContain allBooksId
                        it shouldNotContain c.data.id.value
                    }
                    h.bookAccessPolicy.canAccess("member", UserRole.MEMBER, "book1").shouldBeFalse()
                    // removeBookFromCollection bumps the revision once for the removal itself; the
                    // failed reconcile write must NOT bump it a second time for a write that never
                    // actually happened.
                    h.revisionTouch.touched.count { it == "book1" } shouldBe 1
                }
            }
        }
    })

/** The test library's live INBOX id, resolved the way the release itself resolves it. */
private suspend fun inboxIdOf(service: CollectionServiceImpl): String {
    val inbox = service.getOrCreateInbox("test-library")
    require(inbox is AppResult.Success)
    return inbox.data.id.value
}

/** Asserts this is a [CollectionError.ReleaseIncomplete] failure naming exactly [failedBookIds]. */
private fun AppResult<Unit>.shouldBeReleaseIncomplete(failedBookIds: List<String>): CollectionError.ReleaseIncomplete {
    val failure = shouldBeInstanceOf<AppResult.Failure>()
    val incomplete = failure.error.shouldBeInstanceOf<CollectionError.ReleaseIncomplete>()
    incomplete.failedBookIds shouldBe failedBookIds
    return incomplete
}

/** A live membership of [bookId] in [collectionId], written straight through the repository. */
private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "seed-$collectionId-$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
        deletedAt = null,
    )

/** A live read grant on [collectionId] for [userId], so they are in that collection's audience. */
private suspend fun CollectionAccessHarness.readGrant(
    collectionId: String,
    userId: String,
) {
    grantRepo
        .upsert(
            CollectionShareSyncPayload(
                id = "grant-$collectionId-$userId",
                collectionId = collectionId,
                sharedWithUserId = userId,
                sharedByUserId = "admin",
                permission = SharePermission.Read,
                revision = 0L,
                updatedAt = 0L,
                deletedAt = null,
            ),
        ).shouldBeInstanceOf<AppResult.Success<*>>()
}
