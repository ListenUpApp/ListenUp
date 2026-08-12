@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
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
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * PERF-05: [CollectionServiceImpl.listCollections] batches its per-collection book count
 * ([CollectionBookRepository.countLiveForCollections]) and reconstructs each caller Decision from
 * data already in hand instead of one `countLiveForCollection` + one `accessPolicy.decide()` round
 * trip per collection. This proves the batched path reports the same per-collection
 * bookCount/permission/isOwner as the un-batched `summarize(collection, caller)` path would for an
 * owner and a share recipient. A sibling of [CollectionServiceImplTest] (split out per Detekt's
 * `LargeClass`), with its own small self-contained fixture — mirrors
 * [CollectionServiceImplSetBookCollectionsTest]'s pattern of not sharing the parent spec's local
 * helpers (which are scoped inside its `FunSpec` lambda and unreachable from a sibling file).
 */
class CollectionServiceImplListCountsTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun makeService(db: SqlTestDatabases): CollectionServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver)
            val grantRepo = CollectionGrantRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo = CollectionBookRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver),
                grantRepo = grantRepo,
                accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
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

        test("listCollections reports correct per-collection book counts for owned and shared collections") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("b1")
                sql.seedTestBook("b2")
                sql.seedTestBook("b3")
                runTest {
                    val service = makeService(db)
                    val owner = service.actAs("u1")
                    val colOwned = owner.createCollection("test-library", "Owned")
                    val colShared = owner.createCollection("test-library", "SharedOut")
                    require(colOwned is AppResult.Success)
                    require(colShared is AppResult.Success)

                    owner.addBookToCollection(colOwned.data.id, BookId("b1"))
                    owner.addBookToCollection(colOwned.data.id, BookId("b2"))
                    owner.addBookToCollection(colShared.data.id, BookId("b3"))

                    val grantRepo = CollectionGrantRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry(), driver = db.driver)
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share-counts",
                            collectionId = colShared.data.id.value,
                            sharedWithUserId = "u2",
                            sharedByUserId = "u1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val u2 = service.actAs("u2")
                    val u2ColUnshared = u2.createCollection("test-library", "U2Owned")
                    require(u2ColUnshared is AppResult.Success)

                    // Owner's view: both collections it owns, Write, isOwner, with their real counts.
                    val ownerList = owner.listCollections()
                    require(ownerList is AppResult.Success)
                    val ownerById = ownerList.data.associateBy { it.id }
                    ownerById.getValue(colOwned.data.id).bookCount shouldBe 2L
                    ownerById.getValue(colOwned.data.id).isOwner shouldBe true
                    ownerById.getValue(colOwned.data.id).callerPermission shouldBe SharePermission.Write
                    ownerById.getValue(colShared.data.id).bookCount shouldBe 1L
                    ownerById.getValue(colShared.data.id).isOwner shouldBe true
                    ownerById.getValue(colShared.data.id).callerPermission shouldBe SharePermission.Write

                    // u2's view: an owned empty collection (0 books) plus the shared one (1 book, Read).
                    val u2List = u2.listCollections()
                    require(u2List is AppResult.Success)
                    val u2ById = u2List.data.associateBy { it.id }
                    u2ById.getValue(u2ColUnshared.data.id).bookCount shouldBe 0L
                    u2ById.getValue(u2ColUnshared.data.id).isOwner shouldBe true
                    u2ById.getValue(colShared.data.id).bookCount shouldBe 1L
                    u2ById.getValue(colShared.data.id).isOwner shouldBe false
                    u2ById.getValue(colShared.data.id).callerPermission shouldBe SharePermission.Read
                }
            }
        }
    })
