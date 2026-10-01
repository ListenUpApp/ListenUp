@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
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
import com.calypsan.listenup.server.testing.makeBooksVisibleTo
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Only admins write collections (product rule, 2026-10-01).
 *
 * Being in any normal collection takes a book out of ALL_BOOKS, so a member who could curate a
 * collection could hide a public book from everyone else. A member's ownership of a collection
 * (legacy rows from before this rule) or a `write` share therefore confers read access at most —
 * every write path refuses them.
 */
class CollectionServiceImplMembersCannotWriteTest :
    FunSpec({

        fun principalFor(
            userId: String,
            role: UserRole,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun makeService(
            db: SqlTestDatabases,
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver)
            val grantRepo = CollectionGrantRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver)
            return CollectionServiceImpl(
                collectionRepo = collectionRepo,
                collectionBookRepo =
                    CollectionBookRepository(db = db.sql, bus = bus, registry = registry, driver = db.driver),
                grantRepo = grantRepo,
                accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
                bookAccessPolicy = BookAccessPolicy(db.sql, db.driver),
                permissionPolicy = UserPermissionPolicy(db.sql),
                bus = bus,
                sql = db.sql,
                clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L)),
                bookRevisionTouch = FakeBookRevisionTouch(),
                principal = principalFor(userId, role),
            )
        }

        /** A collection owned by member `u1`, as rows from before this rule exist on real servers. */
        suspend fun SqlTestDatabases.seedMemberOwnedCollection(): CollectionId {
            CollectionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver).upsert(
                CollectionSyncPayload(
                    id = "member-col",
                    libraryId = "test-library",
                    ownerId = "u1",
                    name = "Mine",
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
            return CollectionId("member-col")
        }

        suspend fun SqlTestDatabases.grant(
            collectionId: CollectionId,
            userId: String,
            permission: SharePermission,
        ) {
            CollectionGrantRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver).upsert(
                CollectionShareSyncPayload(
                    id = "share-$userId",
                    collectionId = collectionId.value,
                    sharedWithUserId = userId,
                    sharedByUserId = "admin",
                    permission = permission,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
        }

        test("a member cannot create a collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val result = makeService(this@withSqlDatabase, "u1").createCollection("test-library", "Mine")
                    require(result is AppResult.Failure)
                    result.error.shouldBeInstanceOf<CollectionError.Forbidden>()
                }
            }
        }

        test("an admin can create a collection and owns it with write") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin")
                runTest {
                    val result =
                        makeService(this@withSqlDatabase, "admin", UserRole.ADMIN)
                            .createCollection("test-library", "Kids")
                    require(result is AppResult.Success)
                    result.data.isOwner shouldBe true
                    result.data.callerPermission shouldBe SharePermission.Write
                }
            }
        }

        test("a member who owns a legacy collection can read it but cannot add, rename, delete or share") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest {
                    val collectionId = db.seedMemberOwnedCollection()
                    db.makeBooksVisibleTo("u1", "book1")
                    val member = makeService(db, "u1")

                    val summary = member.getCollection(collectionId)
                    require(summary is AppResult.Success)
                    summary.data.isOwner shouldBe true
                    summary.data.callerPermission shouldBe SharePermission.Read

                    member.addBookToCollection(collectionId, BookId("book1")).forbidden()
                    member.removeBookFromCollection(collectionId, BookId("book1")).forbidden()
                    member.renameCollection(collectionId, "Renamed").forbidden()
                    member.deleteCollection(collectionId).forbidden()
                    member.shareCollection(collectionId, "u2", SharePermission.Read).forbidden()
                    member.listShares(collectionId).forbidden()
                }
            }
        }

        test("a member's write share confers read only — adding a book is forbidden") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin")
                sql.seedTestUser("u2")
                sql.seedTestBook("book1")
                runTest {
                    val admin = makeService(db, "admin", UserRole.ADMIN)
                    val created = admin.createCollection("test-library", "Family")
                    require(created is AppResult.Success)
                    val collectionId = created.data.id
                    db.grant(collectionId, "u2", SharePermission.Write)
                    db.makeBooksVisibleTo("u2", "book1")

                    val member = makeService(db, "u2")
                    val summary = member.getCollection(collectionId)
                    require(summary is AppResult.Success)
                    summary.data.callerPermission shouldBe SharePermission.Read

                    member.addBookToCollection(collectionId, BookId("book1")).forbidden()
                }
            }
        }
    })

private fun AppResult<*>.forbidden() {
    require(this is AppResult.Failure) { "expected Forbidden, got $this" }
    error.shouldBeInstanceOf<CollectionError.Forbidden>()
}
