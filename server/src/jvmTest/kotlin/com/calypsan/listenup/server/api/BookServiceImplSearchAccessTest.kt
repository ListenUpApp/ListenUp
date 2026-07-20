@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
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
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Access-gate tests for [BookServiceImpl.searchBooks] — the FTS5 id seam that feeds
 * the client's server-search path (and from there cover/prepare). A member's query
 * must return only ids they can reach; an inaccessible book matching the term must
 * not leak its existence through the result list. ROOT/ADMIN bypass the filter, which
 * doubles as the control proving the term genuinely matches both books.
 */
class BookServiceImplSearchAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): SearchAccessFixture {
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
            return SearchAccessFixture(
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
            )
        }

        test("member searchBooks omits a private book matching the query") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "hidden", title = "Dragon Hidden"))
                    f.bookRepo.upsert(
                        bookPayloadFixture(id = "public", title = "Dragon Public", rootRelPath = "books/public"),
                    )
                    f.collectionRepo.upsert(searchCollectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(searchMembership("private-col", "hidden"))
                    // "public" is reachable the simplest pure-union way: a member-owned collection
                    // (owner branch — no grant, no system user needed).
                    f.collectionRepo.upsert(searchCollectionFixture("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(searchMembership("owned-col", "public"))

                    val scoped = f.service.copyWith(searchPrincipalFor("member", UserRole.MEMBER))
                    val result = scoped.searchBooks("Dragon", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data shouldContainExactlyInAnyOrder listOf(BookId("public"))
                }
            }
        }

        test("admin searchBooks returns both books matching the query (control)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture(id = "hidden", title = "Dragon Hidden"))
                    f.bookRepo.upsert(
                        bookPayloadFixture(id = "public", title = "Dragon Public", rootRelPath = "books/public"),
                    )
                    f.collectionRepo.upsert(searchCollectionFixture("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(searchMembership("private-col", "hidden"))

                    val scoped = f.service.copyWith(searchPrincipalFor("admin", UserRole.ADMIN))
                    val result = scoped.searchBooks("Dragon", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data shouldContainExactlyInAnyOrder listOf(BookId("hidden"), BookId("public"))
                }
            }
        }
    })

private data class SearchAccessFixture(
    val service: BookServiceImpl,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun searchPrincipalFor(
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

private fun searchCollectionFixture(
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

private fun searchMembership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "${collectionId}:${bookId}",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
