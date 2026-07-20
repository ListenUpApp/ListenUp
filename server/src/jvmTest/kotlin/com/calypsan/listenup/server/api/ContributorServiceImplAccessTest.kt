@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
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
 * Access-gate tests for [ContributorServiceImpl.listBooksByContributor] — proves a member
 * who can reach one book by a contributor never receives a book by the same contributor
 * they can't access. Without the gate the listing returned the FULL [BookSyncPayload]
 * aggregate of every book to any authenticated caller — a metadata leak over both RPC and
 * REST. ROOT/ADMIN see every book.
 */
class ContributorServiceImplAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): ContributorAccessFixture {
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
            val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
            val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver)
            val service =
                ContributorServiceImpl(
                    contributorRepo = contributorRepo,
                    bookRepo = bookRepo,
                    reindexer = reindexer,
                    sqlDb = sql,
                    accessPolicy = BookAccessPolicy(sql, driver),
                )
            return ContributorAccessFixture(
                service = service,
                contributorRepo = contributorRepo,
                bookRepo = bookRepo,
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
            )
        }

        test("member sees only the accessible book, never a private book by the same contributor") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    val contributorId = f.contributorRepo.resolveOrCreate(name = "Brandon Sanderson", sortName = null)
                    f.bookRepo.upsert(contributorBookFixture("public-book", "Public", contributorId))
                    f.bookRepo.upsert(contributorBookFixture("private-book", "Private", contributorId))
                    // public-book: reachable via a member-owned collection (owner branch, no grant).
                    f.collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(membership("owned-col", "public-book"))
                    // private-book: only in a stranger-owned private collection → invisible.
                    f.collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.map { it.id } shouldBe listOf("public-book")
                }
            }
        }

        test("admin sees every book by the contributor, including the private one") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    val contributorId = f.contributorRepo.resolveOrCreate(name = "Brandon Sanderson", sortName = null)
                    f.bookRepo.upsert(contributorBookFixture("public-book", "Public", contributorId))
                    f.bookRepo.upsert(contributorBookFixture("private-book", "Private", contributorId))
                    f.collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    val scoped = f.service.copyWith(principalFor("admin", UserRole.ADMIN))
                    val result = scoped.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.map { it.id }.sorted() shouldBe listOf("private-book", "public-book")
                }
            }
        }
    })

private data class ContributorAccessFixture(
    val service: ContributorServiceImpl,
    val contributorRepo: ContributorRepository,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun contributorBookFixture(
    id: String,
    title: String,
    contributorId: ContributorId,
): BookSyncPayload =
    bookPayloadFixture(
        id = id,
        title = title,
        contributors =
            listOf(
                BookContributorPayload(
                    id = contributorId.value,
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
    )

private fun principalFor(
    userId: String,
    role: UserRole,
): PrincipalProvider =
    PrincipalProvider {
        UserPrincipal(userId = UserId(userId), sessionId = SessionId("session-$userId"), role = role)
    }

private fun privateCollection(
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

private fun membership(
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
