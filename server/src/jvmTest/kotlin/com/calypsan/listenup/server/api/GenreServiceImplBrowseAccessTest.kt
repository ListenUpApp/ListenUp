@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
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
 * Access-gate tests for [GenreServiceImpl.browseBooks] — proves a member browsing a genre
 * receives only the ids of books they can reach, never enumerating the existence of a
 * quarantined or private-collection-only book by the same genre. ROOT/ADMIN see every book.
 */
class GenreServiceImplBrowseAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): BrowseAccessFixture {
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
                GenreServiceImpl(
                    genreRepository = genreRepo,
                    bookRepository = bookRepo,
                    reindexer = reindexer,
                    sqlDb = sql,
                    accessPolicy = BookAccessPolicy(sql, driver),
                )
            return BrowseAccessFixture(
                service = service,
                bookRepo = bookRepo,
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
            )
        }

        test("member browse returns only the accessible book id, never a private book in the genre") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                seedGenreRow(sql, "g-fantasy", "fantasy", "/fantasy")
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture("public-book", "Public"))
                    f.bookRepo.upsert(bookPayloadFixture("private-book", "Private"))
                    sql.transaction {
                        sql.bookGenresQueries.insertIfAbsent(book_id = "public-book", genre_id = "g-fantasy")
                        sql.bookGenresQueries.insertIfAbsent(book_id = "private-book", genre_id = "g-fantasy")
                    }
                    f.collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
                    f.collectionBookRepo.upsert(membership("owned-col", "public-book"))
                    f.collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.browseBooks(GenreId("g-fantasy"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data.map { it.value } shouldBe listOf("public-book")
                }
            }
        }

        test("admin browse returns every book id in the genre, including the private one") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                seedGenreRow(sql, "g-fantasy", "fantasy", "/fantasy")
                runTest {
                    f.bookRepo.upsert(bookPayloadFixture("public-book", "Public"))
                    f.bookRepo.upsert(bookPayloadFixture("private-book", "Private"))
                    sql.transaction {
                        sql.bookGenresQueries.insertIfAbsent(book_id = "public-book", genre_id = "g-fantasy")
                        sql.bookGenresQueries.insertIfAbsent(book_id = "private-book", genre_id = "g-fantasy")
                    }
                    f.collectionRepo.upsert(privateCollection("private-col", owner = "stranger"))
                    f.collectionBookRepo.upsert(membership("private-col", "private-book"))

                    val scoped = f.service.copyWith(principalFor("admin", UserRole.ADMIN))
                    val result = scoped.browseBooks(GenreId("g-fantasy"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data.map { it.value }.sorted() shouldBe listOf("private-book", "public-book")
                }
            }
        }
    })

private data class BrowseAccessFixture(
    val service: GenreServiceImpl,
    val bookRepo: BookRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
)

private fun seedGenreRow(
    sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    id: String,
    slug: String,
    path: String,
) {
    sql.transaction {
        sql.genresQueries.insert(
            id = id,
            name = slug,
            slug = slug,
            path = path,
            parent_id = null,
            depth = 0L,
            sort_order = 0L,
            color = null,
            description = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
        )
    }
}

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
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
