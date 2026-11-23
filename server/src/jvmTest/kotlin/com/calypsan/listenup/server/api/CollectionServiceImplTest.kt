@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.makeBooksVisibleTo
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.collectionAccess
import com.calypsan.listenup.server.testing.collectionBookIds
import com.calypsan.listenup.server.testing.inboxBookIds
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Integration tests for [CollectionServiceImpl].
 *
 * Uses a real in-memory migrated SQLite database + real repositories; no mocks.
 * The acting caller is supplied via a [PrincipalProvider] stub; [actAs] rebinds the
 * service to a chosen `(userId, role)` so a single test can exercise multiple callers.
 */
class CollectionServiceImplTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        /** A principal stub that always reports the given caller. */
        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        fun makeService(db: SqlTestDatabases): CollectionServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = db.newCollectionRepo(bus, registry)
            val collectionBookRepo = db.newCollectionBookRepo(bus, registry)
            val grantRepo = db.newCollectionGrantRepo(bus, registry)
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
                accessPolicy = accessPolicy,
                bookAccessPolicy = BookAccessPolicy(db.sql, db.driver),
                bus = bus,
                sql = db.sql,
                clock = fixedClock,
                bookRevisionTouch = FakeBookRevisionTouch(),
                principal = principalFor("u1"),
            )
        }

        /** Rebinds the service to act as the given caller (mirrors per-request principal binding). */
        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        // ── createCollection ──────────────────────────────────────────────────

        test("createCollection makes the caller the owner") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1", UserRole.ADMIN)
                    val created = service.createCollection("test-library", "Favourites")
                    require(created is AppResult.Success)
                    created.data.name shouldBe "Favourites"
                    created.data.ownerId shouldBe UserId("u1")
                    created.data.isOwner shouldBe true
                    created.data.callerPermission shouldBe SharePermission.Write

                    db.collectionAccess("u1", created.data.id, UserRole.ADMIN).let { access ->
                        access.canAccess shouldBe true
                        access.isOwner shouldBe true
                        access.permission shouldBe SharePermission.Write
                    }
                }
            }
        }

        test("createCollection rejects blank or too-long name") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1", UserRole.ADMIN)

                    val blank = service.createCollection("test-library", "   ")
                    require(blank is AppResult.Failure)
                    blank.error.shouldBeInstanceOf<CollectionError.InvalidInput>()

                    val tooLong = service.createCollection("test-library", "x".repeat(201))
                    require(tooLong is AppResult.Failure)
                    tooLong.error.shouldBeInstanceOf<CollectionError.InvalidInput>()
                }
            }
        }

        // ── addBookToCollection / removeBookFromCollection ──────────────────────

        test("addBookToCollection requires write; owner can; read-share cannot") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1", UserRole.ADMIN)

                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    // The admin owner can add (an admin sees every book; the seed keeps the prior scenario).
                    db.makeBooksVisibleTo("u1", "book1")
                    val ownerAdd = owner.addBookToCollection(collectionId, BookId("book1"))
                    ownerAdd shouldBe AppResult.Success(Unit)

                    // Read-share to u2.
                    val grantRepo =
                        db.newCollectionGrantRepo(ChangeBus(), SyncRegistry())
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = collectionId.value,
                            sharedWithUserId = "u2",
                            sharedByUserId = "u1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    // u2 has read access only → write is Forbidden, not NotFound.
                    val u2 = service.actAs("u2")
                    val u2Add = u2.addBookToCollection(collectionId, BookId("book1"))
                    require(u2Add is AppResult.Failure)
                    u2Add.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("removeBookFromCollection soft-deletes the junction") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(db).actAs("u1", UserRole.ADMIN)
                    val created = service.createCollection("test-library", "Favourites")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    db.makeBooksVisibleTo("u1", "book1")
                    service.addBookToCollection(collectionId, BookId("book1")) shouldBe AppResult.Success(Unit)
                    db.collectionBookIds(collectionId) shouldBe listOf(BookId("book1"))

                    service.removeBookFromCollection(collectionId, BookId("book1")) shouldBe AppResult.Success(Unit)
                    db.collectionBookIds(collectionId) shouldHaveSize 0
                }
            }
        }

        // ── renameCollection ────────────────────────────────────────────────────

        test("renameCollection: owner ok, non-member NotFound") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u3")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1", UserRole.ADMIN)
                    val created = owner.createCollection("test-library", "Original")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    val renamed = owner.renameCollection(collectionId, "Renamed")
                    require(renamed is AppResult.Success)
                    renamed.data.name shouldBe "Renamed"

                    // u3 has no relationship → must not leak existence → NotFound.
                    val stranger = service.actAs("u3")
                    val strangerRename = stranger.renameCollection(collectionId, "Hijacked")
                    require(strangerRename is AppResult.Failure)
                    strangerRename.error.shouldBeInstanceOf<CollectionError.NotFound>()
                }
            }
        }

        // ── deleteCollection ─────────────────────────────────────────────────────

        test("deleteCollection: owner ok; inbox not deletable") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1", UserRole.ADMIN)
                    val created = service.createCollection("test-library", "Disposable")
                    require(created is AppResult.Success)
                    service.deleteCollection(created.data.id) shouldBe AppResult.Success(Unit)
                    // Gone: not even its owner reaches it.
                    db.collectionAccess("u1", created.data.id, UserRole.ADMIN).canAccess shouldBe false

                    // Seed an inbox collection directly; deleting it is rejected.
                    // type='INBOX' must be stamped explicitly — is_inbox column is gone.
                    val collectionRepo =
                        db.newCollectionRepo(ChangeBus(), SyncRegistry())
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "inbox1",
                            libraryId = "test-library",
                            ownerId = "u1",
                            name = "Inbox",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    collectionRepo.setType("inbox1", "INBOX")
                    val inboxDelete = service.deleteCollection(CollectionId("inbox1"))
                    require(inboxDelete is AppResult.Failure)
                    inboxDelete.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                }
            }
        }

        test("deleteCollection cascades to junction rows and active shares") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(db).actAs("u1", UserRole.ADMIN)
                    val created = service.createCollection("test-library", "Disposable")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    // Attach a real junction row and a real active share so the cascade
                    // branches run against NON-empty sets.
                    db.makeBooksVisibleTo("u1", "book1")
                    service.addBookToCollection(collectionId, BookId("book1")) shouldBe AppResult.Success(Unit)

                    val collectionBookRepo =
                        db.newCollectionBookRepo(ChangeBus(), SyncRegistry())
                    val grantRepo =
                        db.newCollectionGrantRepo(ChangeBus(), SyncRegistry())
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = collectionId.value,
                            sharedWithUserId = "u2",
                            sharedByUserId = "u1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    // Preconditions: cascade targets exist and are live.
                    collectionBookRepo.countLiveForCollection(collectionId.value) shouldBe 1L
                    grantRepo.findActiveGrant(collectionId.value, "u2").shouldNotBeNull()

                    service.deleteCollection(collectionId) shouldBe AppResult.Success(Unit)

                    // Cascade: junction rows soft-deleted.
                    collectionBookRepo.findBookIdsForCollection(collectionId.value) shouldHaveSize 0
                    collectionBookRepo.countLiveForCollection(collectionId.value) shouldBe 0L

                    // Cascade: active share soft-deleted.
                    grantRepo.findActiveGrant(collectionId.value, "u2") shouldBe null
                    grantRepo.listActiveGrantsForCollection(collectionId.value) shouldHaveSize 0
                }
            }
        }

        // ── shareCollection / revokeShare ────────────────────────────────────────

        test("shareCollection: owner shares read with u2; u2 now reaches it (Read, not owner)") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1", UserRole.ADMIN)
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    val shared = owner.shareCollection(collectionId, "u2", SharePermission.Read)
                    require(shared is AppResult.Success)
                    shared.data.collectionId shouldBe collectionId
                    shared.data.sharedWithUserId shouldBe UserId("u2")
                    shared.data.permission shouldBe SharePermission.Read

                    db.collectionAccess("u2", collectionId).let { access ->
                        access.canAccess shouldBe true
                        access.isOwner shouldBe false
                        access.permission shouldBe SharePermission.Read
                    }
                }
            }
        }

        test("shareCollection by a member who owns a legacy collection is Forbidden") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                // u1 is a member who owns a collection from before the
                // admin-only rule. Only admins write collections, so sharing is refused outright.
                sql.seedTestUser("u1", UserRoleColumn.MEMBER)
                sql.seedTestUser("u2")
                runTest {
                    db.newCollectionRepo(ChangeBus(), SyncRegistry()).upsert(
                        CollectionSyncPayload(
                            id = "legacy",
                            libraryId = "test-library",
                            ownerId = "u1",
                            name = "Legacy",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    val member = makeService(db).actAs("u1")
                    val shared = member.shareCollection(CollectionId("legacy"), "u2", SharePermission.Read)
                    require(shared is AppResult.Failure)
                    shared.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("shareCollection rejects self-share and non-existent user") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val owner = makeService(db).actAs("u1", UserRole.ADMIN)
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    // Sharing with self → SelfShare.
                    val selfShare = owner.shareCollection(collectionId, "u1", SharePermission.Read)
                    require(selfShare is AppResult.Failure)
                    selfShare.error.shouldBeInstanceOf<CollectionError.SelfShare>()

                    // Sharing with an unseeded user → UserNotFound.
                    val ghost = owner.shareCollection(collectionId, "ghost", SharePermission.Read)
                    require(ghost is AppResult.Failure)
                    ghost.error.shouldBeInstanceOf<CollectionError.UserNotFound>()
                }
            }
        }

        test("shareCollection twice (active) is rejected or updates — AlreadyShared") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val owner = makeService(db).actAs("u1", UserRole.ADMIN)
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    owner.shareCollection(collectionId, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    val again = owner.shareCollection(collectionId, "u2", SharePermission.Read)
                    require(again is AppResult.Failure)
                    again.error.shouldBeInstanceOf<CollectionError.AlreadyShared>()
                }
            }
        }

        test("revokeShare soft-deletes the active share; u2 no longer sees the collection") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1", UserRole.ADMIN)
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    owner.shareCollection(collectionId, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }
                    // Precondition: u2 reaches it.
                    db.collectionAccess("u2", collectionId).canAccess shouldBe true

                    owner.revokeShare(collectionId, "u2") shouldBe AppResult.Success(Unit)

                    db.collectionAccess("u2", collectionId).canAccess shouldBe false
                }
            }
        }

        test("only owner/admin can share/revoke; a write-share member cannot share") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestUser("u3")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1", UserRole.ADMIN)
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    // Grant u2 a write-share.
                    owner.shareCollection(collectionId, "u2", SharePermission.Write).let {
                        require(it is AppResult.Success)
                    }

                    // u2 (write-share member) cannot share onward — Forbidden, not NotFound.
                    val u2 = service.actAs("u2")
                    val u2Share = u2.shareCollection(collectionId, "u3", SharePermission.Read)
                    require(u2Share is AppResult.Failure)
                    u2Share.error.shouldBeInstanceOf<AuthError.PermissionDenied>()

                    // u2 cannot revoke either.
                    val u2Revoke = u2.revokeShare(collectionId, "u3")
                    require(u2Revoke is AppResult.Failure)
                    u2Revoke.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        // ── Inbox (system collection + release flow) ──────────────────────────────

        test("getOrCreateInbox lazily creates one inbox per library, idempotent") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                // No admin required: inbox is owned by the "system" sentinel, not a real user.
                runTest {
                    val service = makeService(db)

                    val first = service.getOrCreateInbox("test-library")
                    require(first is AppResult.Success)
                    first.data.isInbox shouldBe true
                    first.data.name shouldBe "Inbox"
                    first.data.ownerId shouldBe UserId(SYSTEM_OWNER_ID)

                    // Idempotent: a second call returns the same inbox, not a new one.
                    val second = service.getOrCreateInbox("test-library")
                    require(second is AppResult.Success)
                    second.data.id shouldBe first.data.id
                }
            }
        }

        test("inbox is owned by an admin and not deletable via deleteCollection") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(db)
                    val inbox = service.getOrCreateInbox("test-library")
                    require(inbox is AppResult.Success)

                    // The owning admin cannot delete the inbox: it's a protected system collection.
                    val deleteAttempt = service.actAs("admin", UserRole.ADMIN).deleteCollection(inbox.data.id)
                    require(deleteAttempt is AppResult.Failure)
                    deleteAttempt.error.shouldBeInstanceOf<CollectionError.SystemCollectionReadOnly>()
                }
            }
        }

        test("addToInbox adds a book to the library's inbox") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(db)

                    service.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    db.inboxBookIds("test-library") shouldBe listOf(BookId("book1"))

                    // Unknown book → BookNotFound.
                    val ghost = service.addToInbox("ghost", "test-library")
                    require(ghost is AppResult.Failure)
                    ghost.error.shouldBeInstanceOf<CollectionError.BookNotFound>()
                }
            }
        }

        test("releaseBooks moves books out of inbox into staged collections (or none → ALL_BOOKS)") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    val service = makeService(db)
                    val admin = service.actAs("admin", UserRole.ADMIN)

                    // Seed the inbox with two books.
                    service.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)
                    service.addToInbox("book2", "test-library") shouldBe AppResult.Success(Unit)

                    // A real target collection for book1.
                    val collA = admin.createCollection("test-library", "Collection A")
                    require(collA is AppResult.Success)

                    // Release: book1 → [collA], book2 → [] (empty target → ALL_BOOKS, stays public).
                    val released =
                        admin.releaseBooks(
                            LibraryId("test-library"),
                            mapOf(
                                BookId("book1") to listOf(collA.data.id),
                                BookId("book2") to emptyList(),
                            ),
                        )
                    released shouldBe AppResult.Success(Unit)

                    // Inbox is now empty.
                    db.inboxBookIds("test-library") shouldHaveSize 0

                    // book1 landed in collA.
                    db.collectionBookIds(collA.data.id) shouldBe listOf(BookId("book1"))

                    // book2 (empty target) landed in ALL_BOOKS — the public substrate.
                    val allBooks = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    db.collectionBookIds(allBooks.data.id) shouldBe listOf(BookId("book2"))
                }
            }
        }

        test("releaseBooks requires admin") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("u1")
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    service.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    val member = service.actAs("u1", UserRole.MEMBER)

                    val memberRelease = member.releaseBooks(LibraryId("test-library"), mapOf(BookId("book1") to emptyList()))
                    require(memberRelease is AppResult.Failure)
                    memberRelease.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })

// Test-only factory helpers — kept top-level (outside the spec class) so the verbose
// dual-engine wiring (SQLDelight view + Exposed access-filter handle over one file) lives
// in one place and does not inflate the spec class past the detekt LargeClass threshold.
private fun SqlTestDatabases.newCollectionRepo(
    bus: ChangeBus,
    registry: SyncRegistry,
): CollectionRepository = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver)

private fun SqlTestDatabases.newCollectionBookRepo(
    bus: ChangeBus,
    registry: SyncRegistry,
): CollectionBookRepository = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver)

private fun SqlTestDatabases.newCollectionGrantRepo(
    bus: ChangeBus,
    registry: SyncRegistry,
): CollectionGrantRepository = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver)
