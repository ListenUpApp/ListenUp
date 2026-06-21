@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
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
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The member-facing end of the collections-propagation fix: when an admin approves a held
 * (inboxed) book back into the library, the book must become visible to a member *and* its
 * sync revision must advance — so the member's incremental `revision > cursor AND accessible`
 * pull re-delivers it. This is the integration counterpart to [CollectionMembershipRevisionTest],
 * which pins the touch against a fake; here the real [BookRepository] performs the column bump
 * and a real [BookAccessPolicy] resolves visibility, proving the whole seam end-to-end.
 */
class InboxApproveReachesMemberTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        fun makeCollectionService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            bookRevisionTouch: BookRepository,
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
                bookRevisionTouch = bookRevisionTouch,
                principal = principalFor("admin", UserRole.ADMIN),
            )
        }

        fun CollectionServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): CollectionServiceImpl = copyWith(principalFor(userId, role))

        test("a held book is invisible to a member, then visible with an advanced revision after approve") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", userRole = UserRoleColumn.ADMIN)
                sql.seedTestUser("member")
                sql.seedTestBook(bookId = "b1")
                runTest(UnconfinedTestDispatcher()) {
                    val bookRepo = buildBookRepository(sql, driver) // real touch
                    val accessPolicy = BookAccessPolicy(sql, driver)
                    val service = makeCollectionService(sql, driver, bookRevisionTouch = bookRepo)
                    val admin = service.actAs("admin", UserRole.ADMIN)
                    val grantRepo =
                        CollectionGrantRepository(
                            db = sql,
                            bus = ChangeBus(),
                            registry = SyncRegistry(),
                            driver = driver,
                        )

                    // Every member holds a default ALL_BOOKS grant in production; mirror that here
                    // so the member can see books once they reach the public substrate.
                    val allBooks = service.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "member-all-books-grant",
                            collectionId = allBooks.data.id.value,
                            sharedWithUserId = "member",
                            sharedByUserId = "system",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                            deletedAt = null,
                        ),
                    )

                    val inbox = admin.getOrCreateInbox("test-library")
                    require(inbox is AppResult.Success)
                    admin.addBookToCollection(inbox.data.id, BookId("b1")).let {
                        require(it is AppResult.Success)
                    }

                    // Held → a member cannot see it.
                    accessPolicy.canAccess("member", UserRole.MEMBER, "b1") shouldBe false
                    val revisionBeforeApprove = readBookRevision(sql, "b1")

                    // Approve to library (empty target list → ALL_BOOKS → public substrate).
                    admin.releaseBooks("test-library", mapOf("b1" to emptyList<String>())).let {
                        require(it is AppResult.Success)
                    }

                    // Approved → the book joined ALL_BOOKS, so the granted member can now see it,
                    // AND its revision advanced, so the member's incremental
                    // `revision > cursor AND accessible` pull will deliver it.
                    accessPolicy.canAccess("member", UserRole.MEMBER, "b1") shouldBe true
                    readBookRevision(sql, "b1") shouldBeGreaterThan revisionBeforeApprove
                }
            }
        }
    })

/** Reads the current `books.revision` for [id] directly from [sql]. */
private fun readBookRevision(
    sql: ListenUpDatabase,
    id: String,
): Long =
    sql.booksQueries
        .selectById(id)
        .executeAsOne()
        .revision

/**
 * Constructs a real [BookRepository] wired to [sql] and [driver] — used here as the
 * [BookRevisionTouch] so the approve path performs the genuine revision-column bump (not a
 * recording fake). Mirrors the builder in `BookRepositoryTouchRevisionTest`, which is
 * `private` to that file.
 */
private fun buildBookRepository(
    sql: ListenUpDatabase,
    driver: SqlDriver,
): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}
