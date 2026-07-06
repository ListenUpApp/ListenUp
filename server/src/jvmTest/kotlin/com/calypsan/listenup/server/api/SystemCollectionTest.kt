@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.collectionAccessHarness
import com.calypsan.listenup.server.testing.grantAllBooks
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [CollectionServiceImpl.getOrCreateSystemCollection].
 *
 * Drives the generalized find-or-create against a real in-memory Flyway-migrated SQLite
 * database + real repositories; no mocks. Asserts the server-only `collections.type`
 * column is set on creation, the name follows the system type, and the create is idempotent.
 * Also covers the rename/delete guards: system collections (ALL_BOOKS, INBOX) reject
 * [CollectionServiceImpl.renameCollection]/[CollectionServiceImpl.deleteCollection] with
 * [com.calypsan.listenup.api.error.CollectionError.SystemCollectionReadOnly], while a NORMAL
 * collection still renames.
 *
 * System collections are owned by the `"system"` sentinel id — not by any real admin user.
 * This means creation succeeds even when no admin user exists in the database (e.g. at
 * library bootstrap, before any user has registered).
 */
class SystemCollectionTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.ADMIN,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.ADMIN,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        fun makeService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
        ): CollectionServiceImpl {
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
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
                accessPolicy = accessPolicy,
                permissionPolicy = UserPermissionPolicy(sql),
                bus = bus,
                sql = sql,
                clock = fixedClock,
                bookRevisionTouch = FakeBookRevisionTouch(),
                principal = PrincipalProvider { null },
            )
        }

        /** Reads the server-only `collections.type` column for [id], or null if absent. */
        fun ListenUpDatabase.typeColumnOf(id: String): String? = collectionsQueries.selectById(id).executeAsOneOrNull()?.type

        test("getOrCreateSystemCollection creates ALL_BOOKS with type column, name, and system owner") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val service = makeService(sql, driver)

                    val result = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(result is AppResult.Success)
                    result.data.name shouldBe "All Books"
                    result.data.ownerId shouldBe UserId(SYSTEM_OWNER_ID)

                    sql.typeColumnOf(result.data.id.value) shouldBe "ALL_BOOKS"
                }
            }
        }

        test("getOrCreateSystemCollection succeeds with no admin user present") {
            withSqlDatabase {
                // No seedTestUser call — the "system" sentinel must not require an admin.
                sql.seedTestLibraryAndFolder()
                runTest {
                    val service = makeService(sql, driver)

                    val result = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(result is AppResult.Success) {
                        "expected Success but got $result — system collection must not require an admin user"
                    }
                    result.data.ownerId shouldBe UserId(SYSTEM_OWNER_ID)
                }
            }
        }

        test("getOrCreateSystemCollection is idempotent — second call returns the same collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val service = makeService(sql, driver)

                    val first = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(first is AppResult.Success)
                    val second = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(second is AppResult.Success)

                    second.data.id shouldBe first.data.id
                }
            }
        }

        test("getOrCreateSystemCollection INBOX is distinct from ALL_BOOKS and isInbox is derived from type") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val service = makeService(sql, driver)

                    val allBooks = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)

                    val inbox = service.getOrCreateSystemCollection("test-library", SystemCollectionType.INBOX)
                    require(inbox is AppResult.Success)
                    inbox.data.name shouldBe "Inbox"
                    inbox.data.isInbox shouldBe true
                    inbox.data.ownerId shouldBe UserId(SYSTEM_OWNER_ID)

                    (inbox.data.id == allBooks.data.id) shouldBe false

                    sql.typeColumnOf(inbox.data.id.value) shouldBe "INBOX"
                }
            }
        }

        // ── System collection rename/delete guards ────────────────────────────

        test("renameCollection ALL_BOOKS returns SystemCollectionReadOnly") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(sql, driver)

                    val allBooks = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)

                    val result = service.actAs("admin").renameCollection(allBooks.data.id, "Renamed")
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                }
            }
        }

        test("deleteCollection INBOX returns SystemCollectionReadOnly") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(sql, driver)

                    val inbox = service.getOrCreateSystemCollection("test-library", SystemCollectionType.INBOX)
                    require(inbox is AppResult.Success)

                    val result = service.actAs("admin").deleteCollection(inbox.data.id)
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                }
            }
        }

        test("deleteCollection ALL_BOOKS returns SystemCollectionReadOnly") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(sql, driver)

                    val allBooks = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)

                    val result = service.actAs("admin").deleteCollection(allBooks.data.id)
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                }
            }
        }

        // ── System collection share guards (plan 090) ────────────────────────

        test("revokeShare on ALL_BOOKS returns SystemCollectionReadOnly and preserves the member's default grant") {
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
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()

                    val result = admin.revokeShare(CollectionId(allBooksId), "m")

                    require(result is AppResult.Failure) {
                        "expected SystemCollectionReadOnly but revoke returned $result — the default grant was deleted"
                    }
                    result.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                    // The member's default grant and their library visibility survive.
                    h.grantRepo.findActiveGrant(allBooksId, "m").shouldNotBeNull()
                    h.bookAccessPolicy.canAccess("m", UserRole.MEMBER, "B").shouldBeTrue()
                }
            }
        }

        test("shareCollection on INBOX returns SystemCollectionReadOnly and creates no grant") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val inbox = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.INBOX)
                    require(inbox is AppResult.Success)
                    val inboxId = inbox.data.id.value

                    val result = admin.shareCollection(CollectionId(inboxId), "m", SharePermission.Read)

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                    h.grantRepo.findActiveGrant(inboxId, "m").shouldBeNull()
                }
            }
        }

        test("updateShare on ALL_BOOKS returns SystemCollectionReadOnly and keeps the grant at Read") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("m")
                runTest {
                    val h = collectionAccessHarness()
                    val admin = h.service.actAs("admin", UserRole.ADMIN)

                    val allBooks = admin.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    h.grantAllBooks(allBooksId, "m")

                    val result = admin.updateShare(CollectionId(allBooksId), "m", SharePermission.Write)

                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                    h.grantRepo.findActiveGrant(allBooksId, "m")!!.permission shouldBe SharePermission.Read
                }
            }
        }

        test("renameCollection on a normal collection succeeds") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(sql, driver)
                    val adminService = service.actAs("admin")

                    val created = adminService.createCollection("test-library", "My Playlist")
                    require(created is AppResult.Success)

                    val result = adminService.renameCollection(created.data.id, "New Name")
                    require(result is AppResult.Success)
                    result.data.name shouldBe "New Name"
                }
            }
        }
    })
