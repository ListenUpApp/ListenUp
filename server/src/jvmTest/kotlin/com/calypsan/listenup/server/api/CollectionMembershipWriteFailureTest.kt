package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
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
 * reports [AppResult.Success]. These tests drive the real [CollectionServiceImpl] over a real
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
                                failingPairs = setOf(privateId to "book1"),
                            )
                        }
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    // The release still reports Success — the point of the bug: a swallowed write
                    // failure never surfaces to the caller.
                    admin.releaseBooks(
                        LibraryId("test-library"),
                        mapOf(BookId("book1") to listOf(CollectionId(privateId))),
                    ) shouldBe AppResult.Success(Unit)

                    // The dangerous outcome this test guards: the book must NOT have been re-homed
                    // into ALL_BOOKS (which would make it visible to every member) despite its
                    // intended private-collection write failing.
                    h.bookAccessPolicy.canAccess("member", UserRole.MEMBER, "book1").shouldBeFalse()
                    h.junctionDiagnostic("book1").let {
                        it shouldNotContain allBooksId
                        // The private write genuinely failed — no row for it either.
                        it shouldNotContain privateId
                    }
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
