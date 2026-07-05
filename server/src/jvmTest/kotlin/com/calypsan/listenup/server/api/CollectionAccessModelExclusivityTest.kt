@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.db.UserRoleColumn
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
import kotlinx.coroutines.test.runTest

/**
 * Enforces the definitive access model's exclusivity invariant (regression from #680/#730):
 * **a book is in ALL_BOOKS IFF it belongs to no other (non-system) collection.** Curating a
 * book into a real collection must remove it from the everyone-visible ALL_BOOKS substrate;
 * removing its last real membership must return it.
 *
 * These tests drive the real [CollectionServiceImpl] over a real Flyway-migrated in-memory SQLite
 * db via the shared [collectionAccessHarness]. Every case now carries a **load-bearing**
 * [BookAccessPolicy.canAccess] assertion — what a member actually sees — while the junction-state
 * check is demoted to a `junctionDiagnostic(...)` *mechanism diagnostic* that only makes a failure
 * legible. That inversion is deliberate: #680/#730 slipped because the tests pinned junction state,
 * which is legitimately rewritten during a refactor, while the visible-access invariant broke
 * silently. The canonical exclusivity money-shot (curate-out denies a non-member) and the union
 * case now live in `AccessInvariantMatrixTest` (rows I1/I2); these cover the remaining mutation
 * paths — setBookCollections, releaseBooks, deleteCollection, and the never-orphan sweep.
 */
class CollectionAccessModelExclusivityTest :
    FunSpec({

        test("adding a book to a real collection denies it to an ALL_BOOKS member; removing the last restores it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B")) shouldBe AppResult.Success(Unit)
                    h.grantAllBooks(allBooksId, "m")
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)

                    // First real membership ⇒ auto-LEAVE ALL_BOOKS ⇒ the ALL_BOOKS member loses access.
                    admin.addBookToCollection(c.data.id, BookId("B")) shouldBe AppResult.Success(Unit)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()
                    // mechanism diagnostic — not the invariant this test asserts.
                    h.junctionDiagnostic("B").let {
                        it shouldContain c.data.id.value
                        it shouldNotContain allBooksId
                    }

                    // Last real membership removed ⇒ auto-RETURN to ALL_BOOKS ⇒ access restored.
                    admin.removeBookFromCollection(c.data.id, BookId("B")) shouldBe AppResult.Success(Unit)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                    h.junctionDiagnostic("B").let {
                        it shouldContain allBooksId
                        it shouldNotContain c.data.id.value
                    }
                }
            }
        }

        test("setBookCollections([x]) denies an ALL_BOOKS member; setBookCollections([]) restores access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B"))
                    h.grantAllBooks(allBooksId, "m")

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)

                    admin.setBookCollections(BookId("B"), listOf(c.data.id)) shouldBe AppResult.Success(Unit)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()
                    h.junctionDiagnostic("B").let {
                        it shouldContain c.data.id.value
                        it shouldNotContain allBooksId
                    }

                    // Empty target set: the book must NOT be orphaned — it returns to ALL_BOOKS and access returns.
                    admin.setBookCollections(BookId("B"), emptyList()) shouldBe AppResult.Success(Unit)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                    h.junctionDiagnostic("B").let {
                        it shouldContain allBooksId
                        it shouldNotContain c.data.id.value
                    }
                }
            }
        }

        test("releaseBooks: a sorted book is hidden from an ungranted member; an unsorted book stays public") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("sorted")
                sql.seedTestBook("unsorted")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val inbox = admin.getOrCreateInbox("test-library")
                    require(inbox is AppResult.Success)
                    val inboxId = inbox.data.id.value
                    admin.addToInbox("sorted", "test-library") shouldBe AppResult.Success(Unit)
                    admin.addToInbox("unsorted", "test-library") shouldBe AppResult.Success(Unit)

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)

                    admin.releaseBooks(
                        "test-library",
                        mapOf("sorted" to listOf(c.data.id.value), "unsorted" to emptyList()),
                    ) shouldBe AppResult.Success(Unit)

                    val allBooksId =
                        h.collectionRepo.findSystemCollection("test-library", SYSTEM_TYPE_ALL_BOOKS)?.id
                            ?: error("ALL_BOOKS must exist after an unsorted release")
                    h.grantAllBooks(allBooksId, "m")

                    // Released into a private collection m can't reach ⇒ hidden; released unsorted ⇒ public.
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "sorted").shouldBeFalse()
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "unsorted").shouldBeTrue()

                    h.junctionDiagnostic("sorted").let {
                        it shouldContain c.data.id.value
                        it shouldNotContain inboxId
                        it shouldNotContain allBooksId
                    }
                    h.junctionDiagnostic("unsorted").let {
                        it shouldContain allBooksId
                        it shouldNotContain inboxId
                    }
                }
            }
        }

        test("deleteCollection returns a book to a public ALL_BOOKS member when the deleted collection was its only home") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B"))
                    h.grantAllBooks(allBooksId, "m")

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)
                    admin.addBookToCollection(c.data.id, BookId("B"))
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeFalse()

                    // Deleting C removes B's only real membership → B must not be stranded → access returns.
                    admin.deleteCollection(c.data.id) shouldBe AppResult.Success(Unit)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                    h.junctionDiagnostic("B").let {
                        it shouldContain allBooksId
                        it shouldNotContain c.data.id.value
                    }
                }
            }
        }

        test("never-stranded: no add/remove/set/delete path leaves a book unreachable to the public") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(allBooks.data.id, BookId("B"))
                    h.grantAllBooks(allBooksId, "m")

                    val c1 = admin.createCollection("test-library", "C1")
                    val c2 = admin.createCollection("test-library", "C2")
                    require(c1 is AppResult.Success)
                    require(c2 is AppResult.Success)

                    // Through every mutation the book keeps ≥1 live membership (mechanism diagnostic) …
                    admin.addBookToCollection(c1.data.id, BookId("B"))
                    h.junctionDiagnostic("B").isNotEmpty() shouldBe true
                    admin.addBookToCollection(c2.data.id, BookId("B"))
                    h.junctionDiagnostic("B").isNotEmpty() shouldBe true
                    admin.setBookCollections(BookId("B"), listOf(c2.data.id))
                    h.junctionDiagnostic("B").isNotEmpty() shouldBe true
                    admin.removeBookFromCollection(c2.data.id, BookId("B"))
                    h.junctionDiagnostic("B").isNotEmpty() shouldBe true

                    // … and when it lands uncollected it is reachable by the public — the never-stranded
                    // ACCESS consequence, load-bearing: the book returned to ALL_BOOKS, not into a void.
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()

                    admin.setBookCollections(BookId("B"), emptyList())
                    h.junctionDiagnostic("B").isNotEmpty() shouldBe true
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()

                    admin.deleteCollection(c1.data.id)
                    h.junctionDiagnostic("B").isNotEmpty() shouldBe true
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                }
            }
        }
    })
