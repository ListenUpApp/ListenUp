@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.SeriesId
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
 * Access-gate tests for [SeriesServiceImpl.listBooksBySeries] — proves a member who can
 * reach one book in a series never receives a sibling book they can't access. Without the
 * gate the listing returned the FULL [BookSyncPayload] aggregate (title, description,
 * chapters, file paths, cover ref) of every sibling to any authenticated caller — a
 * metadata leak reachable over both RPC and REST. ROOT/ADMIN see every book.
 */
class SeriesServiceImplAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): SeriesAccessFixture {
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
                SeriesServiceImpl(
                    seriesRepo = seriesRepo,
                    bookRepo = bookRepo,
                    reindexer = reindexer,
                    sqlDb = sql,
                    accessPolicy = BookAccessPolicy(sql, driver),
                )
            return SeriesAccessFixture(
                service = service,
                seriesRepo = seriesRepo,
                bookRepo = bookRepo,
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
            )
        }

        test("member sees only the accessible sibling, never a private sibling in the same series") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    val seriesId = f.seriesRepo.resolveOrCreate("The Stormlight Archive")
                    f.bookRepo.upsert(seriesBookFixture("public-book", "Public", seriesId))
                    f.bookRepo.upsert(seriesBookFixture("private-book", "Private", seriesId))
                    // public-book: reachable via a member-owned collection (owner branch, no grant).
                    f.collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(membership("owned-col", "public-book"))
                    // private-book: only in a stranger-owned private collection → invisible.
                    f.collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.listBooksBySeries(seriesId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.map { it.id } shouldBe listOf("public-book")
                }
            }
        }

        test("admin sees every book in the series, including the private sibling") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    val seriesId = f.seriesRepo.resolveOrCreate("The Stormlight Archive")
                    f.bookRepo.upsert(seriesBookFixture("public-book", "Public", seriesId))
                    f.bookRepo.upsert(seriesBookFixture("private-book", "Private", seriesId))
                    f.collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    val scoped = f.service.copyWith(principalFor("admin", UserRole.ADMIN))
                    val result = scoped.listBooksBySeries(seriesId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.map { it.id }.sorted() shouldBe listOf("private-book", "public-book")
                }
            }
        }
    })

private data class SeriesAccessFixture(
    val service: SeriesServiceImpl,
    val seriesRepo: SeriesRepository,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun seriesBookFixture(
    id: String,
    title: String,
    seriesId: SeriesId,
): BookSyncPayload =
    bookPayloadFixture(
        id = id,
        title = title,
        series = listOf(BookSeriesPayload(id = seriesId.value, name = "The Stormlight Archive", sequence = "1")),
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
