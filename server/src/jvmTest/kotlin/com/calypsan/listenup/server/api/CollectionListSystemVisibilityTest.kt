@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
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
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Regression test for the system-collection visibility bug in
 * [CollectionServiceImpl.listCollections].
 *
 * **Bug (confirmed live):** A non-admin member who holds the default `ALL_BOOKS` grant
 * (which every member receives) could see the `ALL_BOOKS` system collection — and, by
 * extension, `INBOX` — through the `GET /api/v1/collections` REST endpoint. Spec §3.2
 * states these must never appear in a member's collection list.
 *
 * **Fix:** After resolving the member's owned + grant-based set, the non-admin branch
 * filters out any id present in [CollectionRepository.systemCollectionIds]. Admins continue
 * to see the full god-view (including system collections) unaltered.
 *
 * **Inconsistency addressed:** The sync path (`GET /api/v1/sync/collections`) already
 * excluded system collections via `BookAccessPolicy.accessibleCollectionIdsSql`. This test
 * ensures the REST path now matches.
 */
class CollectionListSystemVisibilityTest :
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

        test("listCollections hides ALL_BOOKS and INBOX from members but admin god-view keeps them") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(db)

                    // Bootstrap system collections (as the server does at library start-up).
                    val allBooks =
                        service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val inbox =
                        service.getOrCreateSystemCollection("test-library", SystemCollectionType.INBOX)
                    require(inbox is AppResult.Success)

                    // Create a normal user-owned collection.
                    val normal = service.actAs("u1").createCollection("test-library", "My Shelf")
                    require(normal is AppResult.Success)

                    // Seed the default ALL_BOOKS grant that every member receives — this is the
                    // production path that caused the leak.
                    val grantRepo =
                        CollectionGrantRepository(
                            db = db.sql,
                            bus = ChangeBus(),
                            registry = SyncRegistry(),
                            driver = db.driver,
                        )
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "grant-all-books-u1",
                            collectionId = allBooks.data.id.value,
                            sharedWithUserId = "u1",
                            sharedByUserId = SYSTEM_OWNER_ID,
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    // Member must NOT see ALL_BOOKS or INBOX — only their normal collection.
                    val memberList = service.actAs("u1").listCollections()
                    require(memberList is AppResult.Success)
                    val memberIds = memberList.data.map { it.id }
                    memberIds shouldBe listOf(normal.data.id)
                    // Explicit negative assertions for clarity.
                    (allBooks.data.id in memberIds) shouldBe false
                    (inbox.data.id in memberIds) shouldBe false

                    // Admin god-view must still include ALL_BOOKS and INBOX.
                    val adminList = service.actAs("admin", UserRole.ADMIN).listCollections()
                    require(adminList is AppResult.Success)
                    val adminIds = adminList.data.map { it.id }
                    (allBooks.data.id in adminIds) shouldBe true
                    (inbox.data.id in adminIds) shouldBe true
                    (normal.data.id in adminIds) shouldBe true
                }
            }
        }
    })
