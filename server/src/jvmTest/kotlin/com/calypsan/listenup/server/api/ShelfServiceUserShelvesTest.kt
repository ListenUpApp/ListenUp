@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import app.cash.sqldelight.db.SqlDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Contract and ACL tests for [ShelfServiceImpl.getUserShelves].
 *
 * Proves that:
 * 1. A viewer sees an owner's public shelves with book counts reflecting only
 *    books the viewer can access.
 * 2. Private shelves are never returned (even to the owner via this endpoint).
 * 3. A shelf whose only book is inaccessible to the viewer is excluded entirely
 *    (zero-accessible crown-jewel test).
 * 4. A shelf with one accessible and one inaccessible book has bookCount == 1.
 * 5. An unauthenticated caller receives AppResult.Failure(ShelfError.NotFound).
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks.
 */
class ShelfServiceUserShelvesTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun noPrincipal(): PrincipalProvider = PrincipalProvider { null }

        fun makeService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            callerId: String = "viewer",
            role: UserRole = UserRole.MEMBER,
        ): ShelfServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ShelfServiceImpl(
                shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry),
                shelfBookRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry),
                bookAccessPolicy = BookAccessPolicy(sql, driver),
                readAssembler = ShelfReadAssembler(sql),
                clock = fixedClock,
                principal = principalFor(callerId, role),
            )
        }

        fun ShelfServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): ShelfServiceImpl = copyWith(principalFor(userId, role))

        fun ShelfServiceImpl.actAsUnauthenticated(): ShelfServiceImpl = copyWith(noPrincipal())

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        /**
         * Seeds a private collection owned by [collectionOwner] containing [bookId] so that
         * [bookId] is inaccessible to any user who isn't ROOT/ADMIN and has no explicit share.
         */
        fun makeBookInaccessible(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            bookId: String,
            collectionId: String,
            collectionOwner: String = "stranger",
        ) {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo =
                CollectionRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            kotlinx.coroutines.runBlocking {
                collectionRepo.upsert(
                    CollectionSyncPayload(
                        id = collectionId,
                        libraryId = "test-library",
                        ownerId = collectionOwner,
                        name = collectionId,
                        isInbox = false,
                        revision = 0L,
                        updatedAt = 0L,
                    ),
                )
                collectionBookRepo.upsert(
                    CollectionBookSyncPayload(
                        id = "${collectionId}:${bookId}",
                        collectionId = collectionId,
                        bookId = bookId,
                        createdAt = 0L,
                        revision = 0L,
                    ),
                )
            }
        }

        /**
         * Makes [bookId] visible to [viewerId] the pure-union way: places it in the per-library
         * ALL_BOOKS system collection and grants [viewerId] a read share on it. [viewerId] MUST
         * already be FK-seeded via [seedTestUser] — `collection_grants.principal_id` references
         * `users(id)`.
         */
        fun makeBookAccessible(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            bookId: String,
            viewerId: String,
            collectionId: String = "all-books",
        ) {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo =
                CollectionRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            val grantRepo =
                CollectionGrantRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
            kotlinx.coroutines.runBlocking {
                collectionRepo.upsert(
                    CollectionSyncPayload(
                        id = collectionId,
                        libraryId = "test-library",
                        ownerId = "system",
                        name = "All Books",
                        isInbox = false,
                        revision = 0L,
                        updatedAt = 0L,
                    ),
                )
                collectionBookRepo.upsert(
                    CollectionBookSyncPayload(
                        id = "${collectionId}:${bookId}",
                        collectionId = collectionId,
                        bookId = bookId,
                        createdAt = 0L,
                        revision = 0L,
                    ),
                )
                // Grant id is keyed on (collection, viewer) — NOT the book — so adding a second
                // book to the same ALL_BOOKS collection re-upserts the SAME grant (idempotent)
                // instead of inserting a duplicate that would violate the unique active-grant
                // index on (collection_id, principal_type, principal_id).
                grantRepo.upsert(
                    CollectionShareSyncPayload(
                        id = "$collectionId-grant-$viewerId",
                        collectionId = collectionId,
                        sharedWithUserId = viewerId,
                        sharedByUserId = "system",
                        permission = SharePermission.Read,
                        revision = 0L,
                        updatedAt = 0L,
                    ),
                )
            }
        }

        // ── 1: Returns the owner's public shelves with access-filtered book count ─

        test("getUserShelves returns owner's public shelves with viewer-accessible book count") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("owner")
                sql.seedTestUser("viewer")
                sql.seedTestBook("accessible")
                sql.seedTestBook("hidden")
                makeBookInaccessible(sql, driver, bookId = "hidden", collectionId = "priv-col")
                // "accessible" is reachable to the viewer the pure-union way: ALL_BOOKS + grant.
                makeBookAccessible(sql, driver, bookId = "accessible", viewerId = "viewer")
                runTest {
                    val ownerService = makeService(sql, driver, callerId = "owner").actAs("owner")
                    val shelf = ownerService.createShelf(name = "My Picks", isPrivate = false).value()
                    // Seed both books directly through the repo (bypasses owner-access gate)
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val shelfBookRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)
                    shelfBookRepo.addBook(shelf.id.value, "accessible", userId = "owner")
                    shelfBookRepo.addBook(shelf.id.value, "hidden", userId = "owner")

                    val result =
                        makeService(sql, driver, callerId = "viewer")
                            .getUserShelves(UserId("owner"))
                            .value()

                    result shouldHaveSize 1
                    result.first().name shouldBe "My Picks"
                    // Only the accessible book counts; hidden is excluded.
                    result.first().bookCount shouldBe 1
                }
            }
        }

        // ── 2: Private shelves are excluded ────────────────────────────────────

        test("getUserShelves excludes private shelves") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("owner")
                sql.seedTestUser("viewer")
                sql.seedTestBook("b1")
                // "b1" must be visible to the viewer so the public shelf survives access filtering.
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "viewer")
                runTest {
                    val ownerService = makeService(sql, driver, callerId = "owner").actAs("owner")
                    val publicShelf = ownerService.createShelf(name = "Public", isPrivate = false).value()
                    val privateShelf = ownerService.createShelf(name = "Secret", isPrivate = true).value()
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val shelfBookRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)
                    shelfBookRepo.addBook(publicShelf.id.value, "b1", userId = "owner")
                    shelfBookRepo.addBook(privateShelf.id.value, "b1", userId = "owner")

                    val result =
                        makeService(sql, driver, callerId = "viewer")
                            .getUserShelves(UserId("owner"))
                            .value()

                    result shouldHaveSize 1
                    result.first().name shouldBe "Public"
                }
            }
        }

        // ── 3 (CROWN JEWEL ACL): shelf with zero accessible books is excluded ──

        test("getUserShelves excludes a shelf whose only book the viewer cannot access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("owner")
                sql.seedTestUser("viewer")
                sql.seedTestBook("hidden")
                // "hidden" is gated in a private collection — viewer cannot access it.
                makeBookInaccessible(sql, driver, bookId = "hidden", collectionId = "priv-col")
                runTest {
                    val ownerService = makeService(sql, driver, callerId = "owner").actAs("owner")
                    val shelf = ownerService.createShelf(name = "All Hidden", isPrivate = false).value()
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val shelfBookRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)
                    shelfBookRepo.addBook(shelf.id.value, "hidden", userId = "owner")

                    // Viewer cannot access the only book → shelf must be excluded entirely.
                    val result =
                        makeService(sql, driver, callerId = "viewer")
                            .getUserShelves(UserId("owner"))
                            .value()

                    result shouldHaveSize 0
                }
            }
        }

        // ── 4 (ACL): mixed shelf counts only accessible books ─────────────────

        test("getUserShelves returns bookCount 1 for a shelf with one accessible and one inaccessible book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("owner")
                sql.seedTestUser("viewer")
                sql.seedTestBook("visible")
                sql.seedTestBook("hidden")
                makeBookInaccessible(sql, driver, bookId = "hidden", collectionId = "priv-col")
                // Only "visible" is reachable to the viewer; "hidden" stays gated → count stays 1.
                makeBookAccessible(sql, driver, bookId = "visible", viewerId = "viewer")
                runTest {
                    val ownerService = makeService(sql, driver, callerId = "owner").actAs("owner")
                    val shelf = ownerService.createShelf(name = "Mixed", isPrivate = false).value()
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val shelfBookRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)
                    shelfBookRepo.addBook(shelf.id.value, "visible", userId = "owner")
                    shelfBookRepo.addBook(shelf.id.value, "hidden", userId = "owner")

                    val result =
                        makeService(sql, driver, callerId = "viewer")
                            .getUserShelves(UserId("owner"))
                            .value()

                    result shouldHaveSize 1
                    // Exactly one accessible book; inaccessible does not inflate the count.
                    result.first().bookCount shouldBe 1
                }
            }
        }

        // ── 5: Returns multiple public shelves ────────────────────────────────

        test("getUserShelves returns all public shelves with at least one accessible book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("owner")
                sql.seedTestUser("viewer")
                sql.seedTestBook("b1")
                sql.seedTestBook("b2")
                // Both books reachable to the viewer the pure-union way: ALL_BOOKS + grant.
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "viewer")
                makeBookAccessible(sql, driver, bookId = "b2", viewerId = "viewer")
                runTest {
                    val ownerService = makeService(sql, driver, callerId = "owner").actAs("owner")
                    val shelf1 = ownerService.createShelf(name = "Shelf One", isPrivate = false).value()
                    val shelf2 = ownerService.createShelf(name = "Shelf Two", isPrivate = false).value()
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val shelfBookRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)
                    shelfBookRepo.addBook(shelf1.id.value, "b1", userId = "owner")
                    shelfBookRepo.addBook(shelf2.id.value, "b2", userId = "owner")

                    val result =
                        makeService(sql, driver, callerId = "viewer")
                            .getUserShelves(UserId("owner"))
                            .value()

                    result shouldHaveSize 2
                    result.map { it.name } shouldContainExactlyInAnyOrder listOf("Shelf One", "Shelf Two")
                }
            }
        }

        // ── 6: Unauthenticated caller → NotFound ──────────────────────────────

        test("getUserShelves returns Failure(ShelfError.NotFound) when caller is unauthenticated") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("owner")
                runTest {
                    val result =
                        makeService(sql, driver)
                            .actAsUnauthenticated()
                            .getUserShelves(UserId("owner"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ShelfError.NotFound>()
                }
            }
        }
    })
