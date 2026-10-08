@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FakeBookRevisionTouch
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for who may add a book via [CollectionServiceImpl.addBookToCollection], and what they may add.
 *
 * Only admins write collections, so a member — even one owning a collection from before that rule —
 * is refused with `CollectionError.Forbidden` at the write gate, before any book-visibility check runs:
 * whether or not they can see the book, the answer is the same, so the add can neither hand them an
 * unseen book nor reveal that one exists. An admin sees every live book, so the admin may add a book
 * no member can reach.
 *
 * Each test seeds a real in-memory database, builds the impl with real repos plus
 * [BookAccessPolicy], scopes the caller via [CollectionServiceImpl.copyWith] + a
 * [PrincipalProvider] stub, and asserts the gate's decision.
 */
class CollectionAddBookAccessTest :
    FunSpec({

        /** Builds the unscoped service plus the repos a test needs to seed books, collections and grants. */
        fun SqlTestDatabases.fixture(): AddBookFixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(sql, bus, registry)
            val seriesRepo = SeriesRepository(sql, bus, registry)
            val genreRepo = GenreRepository(sql, bus, registry)
            val bookRepo =
                BookRepository(
                    db = sql,
                    driver = driver,
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                )
            val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver)
            val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver)
            val grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver)
            val service =
                CollectionServiceImpl(
                    collectionRepo = collectionRepo,
                    collectionBookRepo = collectionBookRepo,
                    grantRepo = grantRepo,
                    accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
                    bookAccessPolicy = BookAccessPolicy(sql, driver),
                    permissionPolicy = PermissionPolicy(sql),
                    bus = bus,
                    sql = sql,
                    bookRevisionTouch = FakeBookRevisionTouch(),
                    principal = PrincipalProvider { error("Unscoped — call copyWith") },
                )
            return AddBookFixture(
                service = service,
                bookRepo = bookRepo,
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
            )
        }

        // Was "reports a book the member cannot see as BookNotFound": members can no longer add at all,
        // so the visibility gate is unreachable for them — the write gate refuses first.
        test("a member adding a book they cannot see to their own legacy collection is Forbidden, not BookNotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "hidden", title = "Hidden"))
                    // The book lives only in a stranger's private collection — invisible to the member.
                    f.collectionRepo.upsert(addBookCollectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(addBookMembership("private-col", "hidden"))
                    f.collectionRepo.upsert(addBookCollectionFixture("mine", owner = "member"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.addBookToCollection(CollectionId("mine"), BookId("hidden"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<CollectionError.Forbidden>()
                }
            }
        }

        // Was "accepts a book the member can see via an ALL_BOOKS grant": seeing a book no longer lets
        // a member curate it — curating it would take it out of ALL_BOOKS for everyone else.
        test("a member adding a book they can see via an ALL_BOOKS grant to their own legacy collection is Forbidden") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "visible", title = "Visible"))
                    // ALL_BOOKS is the public substrate: a system collection every member holds a
                    // grant on. Membership in it + the member's grant = visibility under pure union.
                    f.collectionRepo.upsert(addBookCollectionFixture("all-books", owner = "system"))
                    f.collectionBookRepo.upsert(addBookMembership("all-books", "visible"))
                    f.grantRepo.upsert(addBookShare("g1", "all-books", "member"))
                    f.collectionRepo.upsert(addBookCollectionFixture("mine", owner = "member"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.addBookToCollection(CollectionId("mine"), BookId("visible"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<CollectionError.Forbidden>()
                }
            }
        }

        test("admin addBookToCollection accepts a book in a stranger's private collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("admin", UserRoleColumn.ADMIN)
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "hidden", title = "Hidden"))
                    f.collectionRepo.upsert(addBookCollectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(addBookMembership("private-col", "hidden"))
                    f.collectionRepo.upsert(addBookCollectionFixture("admin-col", owner = "admin"))

                    val scoped = f.service.copyWith(principalFor("admin", UserRole.ADMIN))
                    val result = scoped.addBookToCollection(CollectionId("admin-col"), BookId("hidden"))

                    result shouldBe AppResult.Success(Unit)
                }
            }
        }
    })

private data class AddBookFixture(
    val service: CollectionServiceImpl,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
)

private fun principalFor(
    userId: String,
    role: UserRole,
): PrincipalProvider =
    PrincipalProvider {
        UserPrincipal(
            userId = UserId(userId),
            sessionId = SessionId("session-$userId"),
            role = role,
        )
    }

private fun addBookCollectionFixture(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun addBookMembership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun addBookShare(
    id: String,
    collectionId: String,
    userId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = userId,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
        deletedAt = null,
    )
