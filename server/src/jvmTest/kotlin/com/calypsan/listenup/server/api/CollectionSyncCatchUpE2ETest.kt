@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private const val SYNC_PULL_LIMIT = 100

/**
 * Cross-module sync E2E test at the **server / sync-catch-up** level.
 *
 * Collections has no client-side sync handler or Room entity yet — that is
 * Collections-2 — so this test cannot route through a real client engine the way
 * `ClientSyncEngineE2ETest` does for tags/books. Instead it drives the real
 * [CollectionServiceImpl] (the RPC service implementation, through real repositories
 * against a Flyway-migrated SQLite database, no mocks) and then asserts at the two
 * seams Collections-2 will consume:
 *
 *  1. **Service read seam** — `listCollections` reflects owner (u1) vs shared (u2)
 *     correctly: `isOwner` and `callerPermission` differ per caller.
 *  2. **Sync catch-up seam** — `pullSince(userId = null, cursor = 0)` on each of the
 *     three syncable repositories (`collections`, `collection_books`,
 *     `collection_shares`) returns the freshly-written rows. This proves the
 *     collection aggregate is on the sync firehose so a future Collections-2 client
 *     handler can replay it. Collections are a global sync domain (`userScoped =
 *     false`), so `pullSince` ignores the `userId` argument — per-user visibility is
 *     a service-layer concern (and the Collections-1b security layer).
 */
class CollectionSyncCatchUpE2ETest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

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
            val collectionRepo =
                CollectionRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val collectionBookRepo =
                CollectionBookRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val grantRepo =
                CollectionGrantRepository(
                    db = db.sql,
                    bus = bus,
                    registry = registry,
                    driver = db.driver,
                )
            val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
                accessPolicy = accessPolicy,
                permissionPolicy = UserPermissionPolicy(db.sql),
                bus = bus,
                sql = db.sql,
                clock = fixedClock,
                bookRevisionTouch = FakeBookRevisionTouch(),
                principal = principalFor("u1"),
            )
        }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        test("create + add-book + share → listCollections distinguishes owner vs shared, and all three domains replay via catch-up") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest {
                    // ---- Drive the real service end-to-end as u1 ----
                    val service = makeService(db)
                    val owner = service.actAs("u1")

                    val created = owner.createCollection("test-library", "Reading List")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id

                    owner.addBookToCollection(collectionId, BookId("book1")) shouldBe AppResult.Success(Unit)

                    val shared = owner.shareCollection(collectionId, "u2", SharePermission.Read)
                    require(shared is AppResult.Success)

                    // ---- Service read seam: owner vs shared perspective ----
                    val ownerView = owner.listCollections()
                    require(ownerView is AppResult.Success)
                    ownerView.data shouldHaveSize 1
                    ownerView.data.first().let {
                        it.id shouldBe collectionId
                        it.isOwner shouldBe true
                        it.callerPermission shouldBe SharePermission.Write
                    }

                    val sharedView = service.actAs("u2").listCollections()
                    require(sharedView is AppResult.Success)
                    sharedView.data shouldHaveSize 1
                    sharedView.data.first().let {
                        it.id shouldBe collectionId
                        it.isOwner shouldBe false
                        it.callerPermission shouldBe SharePermission.Read
                    }

                    // ---- Sync catch-up seam: each domain replays from cursor 0 ----
                    // Fresh repos over the same DB; collections is a global domain so
                    // pullSince ignores the userId argument and returns every row.
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val collectionRepo =
                        CollectionRepository(
                            db = db.sql,
                            bus = bus,
                            registry = registry,
                            driver = db.driver,
                        )
                    val collectionBookRepo =
                        CollectionBookRepository(
                            db = db.sql,
                            bus = bus,
                            registry = registry,
                            driver = db.driver,
                        )
                    val grantRepo =
                        CollectionGrantRepository(
                            db = db.sql,
                            bus = bus,
                            registry = registry,
                            driver = db.driver,
                        )

                    val collectionsPage = collectionRepo.pullSince(userId = null, cursor = 0, limit = SYNC_PULL_LIMIT)
                    collectionsPage.items.map { it.id } shouldContain collectionId.value
                    collectionsPage.items.first { it.id == collectionId.value }.let {
                        it.name shouldBe "Reading List"
                        it.ownerId shouldBe "u1"
                    }

                    val booksPage = collectionBookRepo.pullSince(userId = null, cursor = 0, limit = SYNC_PULL_LIMIT)
                    booksPage.items
                        .filter { it.collectionId == collectionId.value && it.deletedAt == null }
                        .map { it.bookId } shouldContain "book1"

                    val sharesPage = grantRepo.pullSince(userId = null, cursor = 0, limit = SYNC_PULL_LIMIT)
                    sharesPage.items
                        .first {
                            it.collectionId == collectionId.value && it.sharedWithUserId == "u2" && it.deletedAt == null
                        }.let {
                            it.sharedByUserId shouldBe "u1"
                            it.permission shouldBe SharePermission.Read
                        }
                }
            }
        }
    })
