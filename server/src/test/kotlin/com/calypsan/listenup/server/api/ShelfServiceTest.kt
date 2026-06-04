@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Gating and happy-path tests for [ShelfServiceImpl].
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks.
 * The acting caller is supplied via a [PrincipalProvider] stub; [actAs] rebinds the
 * service to a chosen `(userId, role)` so a single test can exercise multiple callers.
 *
 * The deep access-filtering matrix (non-owner public/private views, discovery hiding)
 * lives in `ShelfAccessTest` (Task 4); this covers ownership gating, validation, and the
 * owner happy path.
 */
class ShelfServiceTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun makeService(db: Database): ShelfServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ShelfServiceImpl(
                shelfRepo = ShelfRepository(db = db, bus = bus, registry = registry),
                shelfBookRepo = ShelfBookRepository(db = db, bus = bus, registry = registry),
                bookAccessPolicy = BookAccessPolicy(db),
                readAssembler = ShelfReadAssembler(db),
                clock = fixedClock,
                principal = principalFor("u1"),
            )
        }

        fun ShelfServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): ShelfServiceImpl = copyWith(principalFor(userId, role))

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        // ── create / list / update / delete ────────────────────────────────────

        test("createShelf then listMyShelves shows it") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createShelf(name = "To Read", description = "later", isPrivate = false).value()
                    created.name shouldBe "To Read"
                    created.description shouldBe "later"
                    created.bookCount shouldBe 0

                    val mine = service.listMyShelves().value()
                    mine shouldHaveSize 1
                    mine.first().id shouldBe created.id
                }
            }
        }

        test("updateShelf changes name, description, and privacy") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createShelf(name = "Old", isPrivate = false).value()

                    val updated =
                        service.updateShelf(created.id, name = "New", description = "fresh", isPrivate = true).value()
                    updated.name shouldBe "New"
                    updated.description shouldBe "fresh"
                    updated.isPrivate shouldBe true
                }
            }
        }

        test("deleteShelf removes it from listMyShelves") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createShelf(name = "Temp").value()

                    service.deleteShelf(created.id).value()
                    service.listMyShelves().value().shouldHaveSize(0)
                }
            }
        }

        test("createShelf with a blank name fails with InvalidName") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val result = makeService(db).actAs("u1").createShelf(name = "   ")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ShelfError.InvalidName>()
                }
            }
        }

        // ── book membership + ordering (owner) ─────────────────────────────────

        test("addBook, reorder, and getShelf reflect the owner's ordering with titles and authors") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestBook("b1")
                seedTestBook("b2")
                seedAuthor(db, bookId = "b1", contributorId = "c1", name = "Ada Lovelace")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val shelf = service.createShelf(name = "Reading").value()

                    service.addBookToShelf(shelf.id, BookId("b1")).value()
                    service.addBookToShelf(shelf.id, BookId("b2")).value()

                    val initial = service.getShelf(shelf.id).value()
                    initial.isOwner shouldBe true
                    initial.bookCount shouldBe 2
                    initial.books.map { it.bookId } shouldContainExactly listOf("b1", "b2")
                    initial.books.first().authors shouldContainExactly listOf("Ada Lovelace")

                    service.reorderShelfBooks(shelf.id, listOf(BookId("b2"), BookId("b1"))).value()
                    val reordered = service.getShelf(shelf.id).value()
                    reordered.books.map { it.bookId } shouldContainExactly listOf("b2", "b1")

                    service.removeBookFromShelf(shelf.id, BookId("b2")).value()
                    service
                        .getShelf(shelf.id)
                        .value()
                        .books
                        .map { it.bookId } shouldContainExactly listOf("b1")
                }
            }
        }

        test("addBookToShelf with a book the owner cannot access fails with NotFound") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestBook("hidden")
                runTest {
                    // "hidden" is in u2's PRIVATE collection — invisible to MEMBER u1.
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
                    val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry)
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "priv",
                            libraryId = "test-library",
                            ownerId = "u2",
                            name = "Private",
                            isInbox = false,
                            isGlobalAccess = false,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(collectionId = "priv", bookId = "hidden", createdAt = 0L, revision = 0L),
                    )

                    val service = makeService(db).actAs("u1")
                    val shelf = service.createShelf(name = "Reading").value()

                    val result = service.addBookToShelf(shelf.id, BookId("hidden"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ShelfError.NotFound>()
                }
            }
        }

        // ── ownership gating (Forbidden vs NotFound) ───────────────────────────

        test("a non-owner MEMBER is Forbidden from mutating another user's shelf") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestBook("b1")
                runTest {
                    val owner = makeService(db).actAs("u1")
                    val shelf = owner.createShelf(name = "Mine").value()

                    val intruder = makeService(db).actAs("u2")
                    intruder.updateShelf(shelf.id, "Hijacked", "", false).expectForbidden()
                    intruder.deleteShelf(shelf.id).expectForbidden()
                    intruder.addBookToShelf(shelf.id, BookId("b1")).expectForbidden()
                    intruder.removeBookFromShelf(shelf.id, BookId("b1")).expectForbidden()
                    intruder.reorderShelfBooks(shelf.id, listOf(BookId("b1"))).expectForbidden()
                }
            }
        }

        test("mutating a non-existent shelf fails with NotFound") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val result = service.updateShelf(ShelfId("ghost"), "x", "", false)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ShelfError.NotFound>()
                }
            }
        }

        test("listMyShelves returns only the caller's own shelves") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val base = makeService(db)
                    base.actAs("u1").createShelf(name = "u1 shelf").value()
                    base.actAs("u2").createShelf(name = "u2 shelf").value()

                    val u1Shelves = base.actAs("u1").listMyShelves().value()
                    u1Shelves shouldHaveSize 1
                    u1Shelves.first().name shouldBe "u1 shelf"
                }
            }
        }

        // ── getShelf access basics (full matrix is ShelfAccessTest) ─────────────

        test("getShelf returns NotFound to a non-owner on a private shelf") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val base = makeService(db)
                    val shelf = base.actAs("u1").createShelf(name = "Secret", isPrivate = true).value()

                    val result = base.actAs("u2").getShelf(shelf.id)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ShelfError.NotFound>()
                }
            }
        }
    })

/** Asserts the result is a [ShelfError.Forbidden] failure. */
private fun AppResult<*>.expectForbidden() {
    this.shouldBeInstanceOf<AppResult.Failure>()
    error.shouldBeInstanceOf<ShelfError.Forbidden>()
}

/** Seeds an `author`-role contributor credit linking [contributorId] ([name]) to [bookId]. */
private fun seedAuthor(
    db: Database,
    bookId: String,
    contributorId: String,
    name: String,
) {
    transaction(db) {
        ContributorTable.insert {
            it[ContributorTable.id] = contributorId
            it[ContributorTable.normalizedName] = name.lowercase()
            it[ContributorTable.name] = name
            it[ContributorTable.sortName] = name
            it[ContributorTable.revision] = 0L
            it[ContributorTable.createdAt] = 0L
            it[ContributorTable.updatedAt] = 0L
        }
        BookContributorTable.insert {
            it[BookContributorTable.bookId] = bookId
            it[BookContributorTable.contributorId] = contributorId
            it[BookContributorTable.role] = "author"
            it[BookContributorTable.ordinal] = 0
        }
    }
}
