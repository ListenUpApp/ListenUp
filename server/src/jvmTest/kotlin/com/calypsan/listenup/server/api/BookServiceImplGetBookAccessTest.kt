@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for the access gate on [BookServiceImpl.getBook] — proves a member cannot
 * fetch a book they can't reach, and the deny is indistinguishable from "absent"
 * (`SyncError.NotFound`, never a `Forbidden` that would leak the book's existence).
 *
 * Each test seeds a real in-memory database, builds the impl with real repos plus
 * [BookAccessPolicy], scopes the caller via [BookServiceImpl.copyWith] + a
 * [PrincipalProvider] stub, and asserts the gate's decision.
 */
class BookServiceImplGetBookAccessTest :
    FunSpec({

        /** Builds the unscoped service plus the repos a test needs to seed collections. */
        fun SqlTestDatabases.fixture(): GetBookFixture {
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
            val service =
                BookServiceImpl(
                    repo = bookRepo,
                    contributorRepo = contributorRepo,
                    seriesRepo = seriesRepo,
                    coverStorage = CoverStorage(),
                    sql = sql,
                    genreRepo = genreRepo,
                    accessPolicy = BookAccessPolicy(sql, driver),
                    permissionPolicy = UserPermissionPolicy(sql),
                    principal = PrincipalProvider { error("Unscoped — call copyWith") },
                )
            return GetBookFixture(
                service = service,
                bookRepo = bookRepo,
                collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                collectionBookRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
                grantRepo =
                    CollectionGrantRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    ),
            )
        }

        test("getBook returns NotFound for a book in a private collection the member can't access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "private-book", title = "Hidden"))
                    f.collectionRepo.upsert(collectionGetBookFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(getBookMembership("private-col", "private-book"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.getBook(BookId("private-book"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        test("getBook returns the book to a member granted via ALL_BOOKS (the public substrate)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "public-book", title = "Public"))
                    // ALL_BOOKS is the public substrate: a system collection every member holds a
                    // grant on. Membership in it + the member's grant = visibility under pure union.
                    f.collectionRepo.upsert(collectionGetBookFixture("all-books", owner = "system"))
                    f.collectionBookRepo.upsert(getBookMembership("all-books", "public-book"))
                    f.grantRepo.upsert(getBookShare("g1", "all-books", "member"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.getBook(BookId("public-book"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    success.data.id shouldBe "public-book"
                }
            }
        }

        test("getBook returns the book to its collection owner / active share") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "owned-book", title = "Owned"))
                    f.collectionRepo.upsert(collectionGetBookFixture("owned-col", owner = "owner"))
                    f.collectionBookRepo.upsert(getBookMembership("owned-col", "owned-book"))

                    val scoped = f.service.copyWith(principalFor("owner", UserRole.MEMBER))
                    val result = scoped.getBook(BookId("owned-book"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    success.data.id shouldBe "owned-book"
                }
            }
        }

        test("admin getBook sees a private/inbox book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "inbox-book", title = "Inbox"))
                    f.collectionRepo.upsert(collectionGetBookFixture("inbox-col", owner = "stranger", isInbox = true))
                    f.collectionBookRepo.upsert(getBookMembership("inbox-col", "inbox-book"))

                    val scoped = f.service.copyWith(principalFor("admin", UserRole.ADMIN))
                    val result = scoped.getBook(BookId("inbox-book"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    success.data.id shouldBe "inbox-book"
                }
            }
        }
    })

private data class GetBookFixture(
    val service: BookServiceImpl,
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

private fun collectionGetBookFixture(
    id: String,
    owner: String,
    isInbox: Boolean = false,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = isInbox,
        revision = 0L,
        updatedAt = 0L,
    )

private fun getBookMembership(
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

private fun getBookShare(
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
