@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Enforces the definitive access model's exclusivity invariant (regression from #680/#730):
 * **a book is in ALL_BOOKS IFF it belongs to no other (non-system) collection.** Curating a
 * book into a real collection must remove it from the everyone-visible ALL_BOOKS substrate;
 * removing its last real membership must return it.
 *
 * These tests drive the real [CollectionServiceImpl] over a real Flyway-migrated in-memory
 * SQLite db and assert visibility through the real [BookAccessPolicy] — the money-shot proves
 * a curated-out book is denied to a non-member who only holds the default ALL_BOOKS grant.
 */
class CollectionAccessModelExclusivityTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        data class Harness(
            val service: CollectionServiceImpl,
            val collectionRepo: CollectionRepository,
            val collectionBookRepo: CollectionBookRepository,
            val grantRepo: CollectionGrantRepository,
            val bookAccessPolicy: BookAccessPolicy,
        )

        fun makeHarness(db: SqlTestDatabases): Harness {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver)
            val collectionBookRepo =
                CollectionBookRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver)
            val grantRepo = CollectionGrantRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver)
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            val service =
                CollectionServiceImpl(
                    collectionRepo = collectionRepo,
                    collectionBookRepo = collectionBookRepo,
                    grantRepo = grantRepo,
                    accessPolicy = accessPolicy,
                    bus = bus,
                    sql = db.sql,
                    clock = fixedClock,
                    permissionPolicy = UserPermissionPolicy(db.sql),
                    bookRevisionTouch = FakeBookRevisionTouch(),
                    principal = principalFor("admin", UserRole.ADMIN),
                )
            return Harness(service, collectionRepo, collectionBookRepo, grantRepo, BookAccessPolicy(db.sql, db.driver))
        }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        /** Grants [userId] the default ALL_BOOKS grant every member holds at registration. */
        suspend fun Harness.grantAllBooks(
            allBooksId: String,
            userId: String,
        ) {
            grantRepo.upsert(
                CollectionShareSyncPayload(
                    id = "grant-$allBooksId-$userId",
                    collectionId = allBooksId,
                    sharedWithUserId = userId,
                    sharedByUserId = "system",
                    permission = SharePermission.Read,
                    revision = 0L,
                    updatedAt = 0L,
                    deletedAt = null,
                ),
            )
        }

        /** The live (non-system-excluded) collection ids the book currently belongs to. */
        suspend fun Harness.liveMemberships(bookId: String): Set<String> = collectionBookRepo.findCollectionIdsForBook(bookId).toSet()

        test("adding a book to a real collection removes it from ALL_BOOKS; removing the last returns it") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("B")
                runTest {
                    val h = makeHarness(db)
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B")) shouldBe AppResult.Success(Unit)
                    h.liveMemberships("B") shouldContain allBooksId

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)

                    // First real membership ⇒ auto-LEAVE ALL_BOOKS.
                    admin.addBookToCollection(c.data.id, BookId("B")) shouldBe AppResult.Success(Unit)
                    h.liveMemberships("B").let {
                        it shouldContain c.data.id.value
                        it shouldNotContain allBooksId
                    }

                    // Last real membership removed ⇒ auto-RETURN to ALL_BOOKS.
                    admin.removeBookFromCollection(c.data.id, BookId("B")) shouldBe AppResult.Success(Unit)
                    h.liveMemberships("B").let {
                        it shouldContain allBooksId
                        it shouldNotContain c.data.id.value
                    }
                }
            }
        }

        test("setBookCollections([x]) leaves ALL_BOOKS; setBookCollections([]) returns to ALL_BOOKS") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("B")
                runTest {
                    val h = makeHarness(db)
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B"))

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)

                    admin.setBookCollections(BookId("B"), listOf(c.data.id)) shouldBe AppResult.Success(Unit)
                    h.liveMemberships("B").let {
                        it shouldContain c.data.id.value
                        it shouldNotContain allBooksId
                    }

                    // Empty target set: the book must NOT be orphaned — it returns to ALL_BOOKS.
                    admin.setBookCollections(BookId("B"), emptyList()) shouldBe AppResult.Success(Unit)
                    h.liveMemberships("B").let {
                        it shouldContain allBooksId
                        it shouldNotContain c.data.id.value
                    }
                }
            }
        }

        test("MONEY SHOT: curating a book out of ALL_BOOKS denies it to a non-member; removing restores access") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = makeHarness(db)
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B"))
                    h.grantAllBooks(allBooksId, "m")

                    // Precondition: m sees B via the default ALL_BOOKS grant.
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B") shouldBe true

                    // Admin curates B into a private collection C that m is NOT a member of.
                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)
                    admin.addBookToCollection(c.data.id, BookId("B")) shouldBe AppResult.Success(Unit)

                    // B left ALL_BOOKS → m can no longer reach it (THIS FAILS BEFORE THE FIX).
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B") shouldBe false

                    // Un-curate → B returns to ALL_BOOKS → m regains access.
                    admin.removeBookFromCollection(c.data.id, BookId("B")) shouldBe AppResult.Success(Unit)
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B") shouldBe true
                }
            }
        }

        test("multi-collection union: book stays visible while any granted real membership remains") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                sql.seedTestBook("B")
                runTest {
                    val h = makeHarness(db)
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B"))
                    h.grantAllBooks(allBooksId, "m")

                    // C1 (shared to m) and C2 (private). B ∈ C1 ∪ C2 ⇒ B leaves ALL_BOOKS.
                    val c1 = admin.createCollection("test-library", "C1")
                    val c2 = admin.createCollection("test-library", "C2")
                    require(c1 is AppResult.Success)
                    require(c2 is AppResult.Success)
                    admin.addBookToCollection(c1.data.id, BookId("B"))
                    admin.addBookToCollection(c2.data.id, BookId("B"))
                    h.grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "grant-c1-m",
                            collectionId = c1.data.id.value,
                            sharedWithUserId = "m",
                            sharedByUserId = "admin",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                            deletedAt = null,
                        ),
                    )
                    h.liveMemberships("B") shouldNotContain allBooksId
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B") shouldBe true

                    // Remove C1: m loses the granted path, but B is still in C2 (not ALL_BOOKS) → invisible to m.
                    admin.removeBookFromCollection(c1.data.id, BookId("B"))
                    h.liveMemberships("B") shouldNotContain allBooksId
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B") shouldBe false

                    // Remove C2 (last real membership) → B returns to ALL_BOOKS → visible to m again.
                    admin.removeBookFromCollection(c2.data.id, BookId("B"))
                    h.liveMemberships("B") shouldContain allBooksId
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B") shouldBe true
                }
            }
        }

        test("releaseBooks: released into a collection ⇒ not in INBOX, not in ALL_BOOKS; released unsorted ⇒ ALL_BOOKS only") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("sorted")
                sql.seedTestBook("unsorted")
                runTest {
                    val h = makeHarness(db)
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

                    h.liveMemberships("sorted").let {
                        it shouldContain c.data.id.value
                        it shouldNotContain inboxId
                        it shouldNotContain allBooksId
                    }
                    h.liveMemberships("unsorted").let {
                        it shouldContain allBooksId
                        it shouldNotContain inboxId
                    }
                }
            }
        }

        test("deleteCollection returns a book to ALL_BOOKS when the deleted collection was its only membership") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("B")
                runTest {
                    val h = makeHarness(db)
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    admin.addBookToCollection(CollectionId(allBooksId), BookId("B"))

                    val c = admin.createCollection("test-library", "C")
                    require(c is AppResult.Success)
                    admin.addBookToCollection(c.data.id, BookId("B"))
                    h.liveMemberships("B") shouldNotContain allBooksId

                    // Deleting C removes B's only real membership → B must not be stranded.
                    admin.deleteCollection(c.data.id) shouldBe AppResult.Success(Unit)
                    h.liveMemberships("B").let {
                        it shouldContain allBooksId
                        it shouldNotContain c.data.id.value
                    }
                }
            }
        }

        test("never-orphan: no add/remove/set/delete path ends with a book in ZERO collections") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("B")
                runTest {
                    val h = makeHarness(db)
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    admin.addBookToCollection(allBooks.data.id, BookId("B"))

                    val c1 = admin.createCollection("test-library", "C1")
                    val c2 = admin.createCollection("test-library", "C2")
                    require(c1 is AppResult.Success)
                    require(c2 is AppResult.Success)

                    admin.addBookToCollection(c1.data.id, BookId("B"))
                    h.liveMemberships("B").isNotEmpty() shouldBe true
                    admin.addBookToCollection(c2.data.id, BookId("B"))
                    h.liveMemberships("B").isNotEmpty() shouldBe true
                    admin.setBookCollections(BookId("B"), listOf(c2.data.id))
                    h.liveMemberships("B").isNotEmpty() shouldBe true
                    admin.removeBookFromCollection(c2.data.id, BookId("B"))
                    h.liveMemberships("B").isNotEmpty() shouldBe true
                    admin.setBookCollections(BookId("B"), emptyList())
                    h.liveMemberships("B").isNotEmpty() shouldBe true
                    admin.deleteCollection(c1.data.id)
                    h.liveMemberships("B").isNotEmpty() shouldBe true
                }
            }
        }
    })
