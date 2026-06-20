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
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Integration tests for [CollectionServiceImpl].
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories; no mocks.
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

        fun makeService(db: Database): CollectionServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db, bus = bus, registry = registry)
            val collectionBookRepo = CollectionBookRepository(db = db, bus = bus, registry = registry)
            val grantRepo = CollectionGrantRepository(db = db, bus = bus, registry = registry)
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
                accessPolicy = accessPolicy,
                permissionPolicy = UserPermissionPolicy(db),
                bus = bus,
                db = db,
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createCollection("test-library", "Favourites")
                    require(created is AppResult.Success)
                    created.data.name shouldBe "Favourites"
                    created.data.ownerId shouldBe UserId("u1")
                    created.data.isOwner shouldBe true
                    created.data.callerPermission shouldBe SharePermission.Write

                    val list = service.listCollections()
                    require(list is AppResult.Success)
                    list.data shouldHaveSize 1
                    list.data.first().id shouldBe created.data.id
                    list.data.first().isOwner shouldBe true
                    list.data.first().callerPermission shouldBe SharePermission.Write
                }
            }
        }

        test("createCollection rejects blank or too-long name") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1")

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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1")

                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    // Owner can add.
                    val ownerAdd = owner.addBookToCollection(collectionId, BookId("book1"))
                    ownerAdd shouldBe AppResult.Success(Unit)

                    // Read-share to u2.
                    val grantRepo = CollectionGrantRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
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
                    u2Add.error.shouldBeInstanceOf<CollectionError.Forbidden>()
                }
            }
        }

        test("removeBookFromCollection soft-deletes the junction") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestBook("book1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createCollection("test-library", "Favourites")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    service.addBookToCollection(collectionId, BookId("book1")) shouldBe AppResult.Success(Unit)
                    service.listCollectionBooks(collectionId).let {
                        require(it is AppResult.Success)
                        it.data shouldBe listOf(BookId("book1"))
                    }

                    service.removeBookFromCollection(collectionId, BookId("book1")) shouldBe AppResult.Success(Unit)
                    service.listCollectionBooks(collectionId).let {
                        require(it is AppResult.Success)
                        it.data shouldHaveSize 0
                    }
                }
            }
        }

        // ── renameCollection ────────────────────────────────────────────────────

        test("renameCollection: owner ok, non-member NotFound") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u3")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1")
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createCollection("test-library", "Disposable")
                    require(created is AppResult.Success)
                    service.deleteCollection(created.data.id) shouldBe AppResult.Success(Unit)
                    // Gone from listing.
                    service.listCollections().let {
                        require(it is AppResult.Success)
                        it.data shouldHaveSize 0
                    }

                    // Seed an inbox collection directly; deleting it is rejected.
                    val collectionRepo = CollectionRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "inbox1",
                            libraryId = "test-library",
                            ownerId = "u1",
                            name = "Inbox",
                            isInbox = true,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    val inboxDelete = service.deleteCollection(CollectionId("inbox1"))
                    require(inboxDelete is AppResult.Failure)
                    inboxDelete.error.shouldBeInstanceOf<CollectionError.InboxNotDeletable>()
                }
            }
        }

        test("deleteCollection cascades to junction rows and active shares") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestBook("book1")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createCollection("test-library", "Disposable")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    // Attach a real junction row and a real active share so the cascade
                    // branches run against NON-empty sets.
                    service.addBookToCollection(collectionId, BookId("book1")) shouldBe AppResult.Success(Unit)

                    val collectionBookRepo = CollectionBookRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                    val grantRepo = CollectionGrantRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
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
                    require(grantRepo.findActiveGrant(collectionId.value, "u2") != null)

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

        // ── listCollections ──────────────────────────────────────────────────────

        test("listCollections returns owned + shared, admin sees all") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val service = makeService(db)

                    // u1 owns colOwned and colSharedOut.
                    val owner = service.actAs("u1")
                    val colOwned = owner.createCollection("test-library", "Owned")
                    val colSharedOut = owner.createCollection("test-library", "SharedOut")
                    require(colOwned is AppResult.Success)
                    require(colSharedOut is AppResult.Success)

                    // u2 owns colU2.
                    val u2 = service.actAs("u2")
                    val colU2 = u2.createCollection("test-library", "U2Owned")
                    require(colU2 is AppResult.Success)

                    // Share colSharedOut with u2 (read).
                    val grantRepo = CollectionGrantRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = colSharedOut.data.id.value,
                            sharedWithUserId = "u2",
                            sharedByUserId = "u1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    // u2 sees own + shared.
                    val u2List = u2.listCollections()
                    require(u2List is AppResult.Success)
                    u2List.data.map { it.id } shouldContainExactlyInAnyOrder
                        listOf(colU2.data.id, colSharedOut.data.id)

                    // Admin sees all three.
                    val admin = service.actAs("admin", UserRole.ADMIN)
                    val adminList = admin.listCollections()
                    require(adminList is AppResult.Success)
                    adminList.data.map { it.id } shouldContainExactlyInAnyOrder
                        listOf(colOwned.data.id, colSharedOut.data.id, colU2.data.id)
                }
            }
        }

        // ── listCollectionBooks ──────────────────────────────────────────────────

        test("listCollectionBooks returns live junction book ids for an accessible collection") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    val service = makeService(db).actAs("u1")
                    val created = service.createCollection("test-library", "Reading List")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    service.addBookToCollection(collectionId, BookId("book1"))
                    service.addBookToCollection(collectionId, BookId("book2"))

                    val books = service.listCollectionBooks(collectionId)
                    require(books is AppResult.Success)
                    books.data shouldContainExactlyInAnyOrder listOf(BookId("book1"), BookId("book2"))
                }
            }
        }

        // ── shareCollection / updateShare / revokeShare / listShares ──────────────

        test("shareCollection: owner shares read with u2; u2 now sees it in listCollections (Read, not owner)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    val shared = owner.shareCollection(collectionId, "u2", SharePermission.Read)
                    require(shared is AppResult.Success)
                    shared.data.collectionId shouldBe collectionId
                    shared.data.sharedWithUserId shouldBe UserId("u2")
                    shared.data.permission shouldBe SharePermission.Read

                    val u2List = service.actAs("u2").listCollections()
                    require(u2List is AppResult.Success)
                    u2List.data shouldHaveSize 1
                    u2List.data.first().id shouldBe collectionId
                    u2List.data.first().isOwner shouldBe false
                    u2List.data.first().callerPermission shouldBe SharePermission.Read
                }
            }
        }

        test("shareCollection by an owner-member without canShare is denied with PermissionDenied") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                // u1 owns the collection but is a member whose canShare is revoked.
                seedTestUser("u1", UserRoleColumn.MEMBER, canShare = false)
                seedTestUser("u2")
                runTest {
                    val owner = makeService(db).actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    // Owner gate passes (they own it) but the canShare gate denies.
                    val shared = owner.shareCollection(collectionId, "u2", SharePermission.Read)
                    require(shared is AppResult.Failure)
                    shared.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("shareCollection rejects self-share and non-existent user") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                runTest {
                    val owner = makeService(db).actAs("u1")
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val owner = makeService(db).actAs("u1")
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

        test("updateShare read→write upgrades u2's permission") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    owner.shareCollection(collectionId, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }

                    val updated = owner.updateShare(collectionId, "u2", SharePermission.Write)
                    require(updated is AppResult.Success)
                    updated.data.permission shouldBe SharePermission.Write

                    // u2 now reads the collection at Write permission.
                    val u2List = service.actAs("u2").listCollections()
                    require(u2List is AppResult.Success)
                    u2List.data.first().callerPermission shouldBe SharePermission.Write
                }
            }
        }

        test("updateShare requires an active share — NotFound otherwise") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val owner = makeService(db).actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)

                    val updated = owner.updateShare(created.data.id, "u2", SharePermission.Write)
                    require(updated is AppResult.Failure)
                    updated.error.shouldBeInstanceOf<CollectionError.NotFound>()
                }
            }
        }

        test("revokeShare soft-deletes the active share; u2 no longer sees the collection") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    owner.shareCollection(collectionId, "u2", SharePermission.Read).let {
                        require(it is AppResult.Success)
                    }
                    // Precondition: u2 sees it.
                    service.actAs("u2").listCollections().let {
                        require(it is AppResult.Success)
                        it.data shouldHaveSize 1
                    }

                    owner.revokeShare(collectionId, "u2") shouldBe AppResult.Success(Unit)

                    service.actAs("u2").listCollections().let {
                        require(it is AppResult.Success)
                        it.data shouldHaveSize 0
                    }
                }
            }
        }

        test("only owner/admin can share/revoke; a write-share member cannot share") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestUser("u3")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1")
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
                    u2Share.error.shouldBeInstanceOf<CollectionError.Forbidden>()

                    // u2 cannot revoke either.
                    val u2Revoke = u2.revokeShare(collectionId, "u3")
                    require(u2Revoke is AppResult.Failure)
                    u2Revoke.error.shouldBeInstanceOf<CollectionError.Forbidden>()
                }
            }
        }

        test("listShares returns active shares for an owner-visible collection") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("u1")
                seedTestUser("u2")
                seedTestUser("u3")
                runTest {
                    val owner = makeService(db).actAs("u1")
                    val created = owner.createCollection("test-library", "Shared")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    owner.shareCollection(collectionId, "u2", SharePermission.Read)
                    owner.shareCollection(collectionId, "u3", SharePermission.Write)

                    val shares = owner.listShares(collectionId)
                    require(shares is AppResult.Success)
                    shares.data.map { it.sharedWithUserId } shouldContainExactlyInAnyOrder
                        listOf(UserId("u2"), UserId("u3"))
                }
            }
        }

        // ── Inbox (system collection + release flow) ──────────────────────────────

        test("getOrCreateInbox lazily creates one inbox per library, idempotent") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                runTest {
                    val service = makeService(db)
                    val inbox = service.getOrCreateInbox("test-library")
                    require(inbox is AppResult.Success)

                    // The owning admin cannot delete the inbox: it's a protected system collection.
                    val deleteAttempt = service.actAs("admin", UserRole.ADMIN).deleteCollection(inbox.data.id)
                    require(deleteAttempt is AppResult.Failure)
                    deleteAttempt.error.shouldBeInstanceOf<CollectionError.InboxNotDeletable>()
                }
            }
        }

        test("addToInbox adds a book to the library's inbox") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)

                    service.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    val inboxBooks = service.actAs("admin", UserRole.ADMIN).listInbox("test-library")
                    require(inboxBooks is AppResult.Success)
                    inboxBooks.data shouldBe listOf(BookId("book1"))

                    // Unknown book → BookNotFound.
                    val ghost = service.addToInbox("ghost", "test-library")
                    require(ghost is AppResult.Failure)
                    ghost.error.shouldBeInstanceOf<CollectionError.BookNotFound>()
                }
            }
        }

        test("releaseBooks moves books out of inbox into staged collections (or none → ALL_BOOKS)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestBook("book1")
                seedTestBook("book2")
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
                            "test-library",
                            mapOf(
                                "book1" to listOf(collA.data.id.value),
                                "book2" to emptyList(),
                            ),
                        )
                    released shouldBe AppResult.Success(Unit)

                    // Inbox is now empty.
                    val inboxBooks = admin.listInbox("test-library")
                    require(inboxBooks is AppResult.Success)
                    inboxBooks.data shouldHaveSize 0

                    // book1 landed in collA.
                    val collABooks = admin.listCollectionBooks(collA.data.id)
                    require(collABooks is AppResult.Success)
                    collABooks.data shouldBe listOf(BookId("book1"))

                    // book2 (empty target) landed in ALL_BOOKS — the public substrate.
                    val allBooks = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksMembers = admin.listCollectionBooks(allBooks.data.id)
                    require(allBooksMembers is AppResult.Success)
                    allBooksMembers.data shouldBe listOf(BookId("book2"))
                }
            }
        }

        test("listInbox / releaseBooks require admin") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("admin", UserRoleColumn.ADMIN)
                seedTestUser("u1")
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    service.addToInbox("book1", "test-library") shouldBe AppResult.Success(Unit)

                    val member = service.actAs("u1", UserRole.MEMBER)

                    val memberList = member.listInbox("test-library")
                    require(memberList is AppResult.Failure)
                    memberList.error.shouldBeInstanceOf<CollectionError.Forbidden>()

                    val memberRelease = member.releaseBooks("test-library", mapOf("book1" to emptyList()))
                    require(memberRelease is AppResult.Failure)
                    memberRelease.error.shouldBeInstanceOf<CollectionError.Forbidden>()
                }
            }
        }
    })
