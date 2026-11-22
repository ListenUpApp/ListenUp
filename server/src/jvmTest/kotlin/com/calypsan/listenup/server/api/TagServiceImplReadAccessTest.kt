@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.TagId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
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
import com.calypsan.listenup.api.dto.FacetStats
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Access-gate tests for [TagServiceImpl]'s READ surface.
 *
 * `BookAccessPolicy`'s own KDoc states the invariant: it is "the single source of truth for
 * book-level visibility… every seam that scopes books to a viewer derives from this one definition
 * rather than re-deriving the rule, so the access boundary can never drift between surfaces."
 * Tag reads were the seam that drifted — they consulted the junction tables directly, so a member
 * could enumerate the ids of books in collections they cannot reach, and read the tags of a book
 * [BookServiceImpl.getBook] would have reported as absent.
 *
 * Every assertion carries a **visible control** — a book the member legitimately sees. Without it a
 * gate that returned nothing at all would pass, and the test would prove only that the query was
 * broken.
 */
class TagServiceImplReadAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): TagReadAccessFixture {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val bookRepo =
                BookRepository(
                    db = sql,
                    driver = driver,
                    bus = bus,
                    registry = registry,
                    contributorRepository = ContributorRepository(sql, bus, registry),
                    seriesRepository = SeriesRepository(sql, bus, registry),
                    genreRepository = GenreRepository(sql, bus, registry),
                )
            val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry, driver = driver)
            val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
            return TagReadAccessFixture(
                service =
                    TagServiceImpl(
                        tagRepository = tagRepo,
                        bookTagRepository = bookTagRepo,
                        sql = sql,
                        accessPolicy = BookAccessPolicy(sql, driver),
                    ),
                bookRepo = bookRepo,
                bookTagRepo = bookTagRepo,
                tagRepo = tagRepo,
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo =
                    CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
            )
        }

        /** Two books with the same tag: one the member can reach, one only a stranger can. */
        suspend fun TagReadAccessFixture.seedPublicAndPrivate() {
            bookRepo.upsert(bookPayloadFixture("public-book", "Public"))
            bookRepo.upsert(bookPayloadFixture("private-book", "Private"))
            tagRepo.upsert(tagPayload("t-noir", "noir"))
            bookTagRepo.upsert(junction("bt-public", "public-book", "t-noir"))
            bookTagRepo.upsert(junction("bt-private", "private-book", "t-noir"))
            collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
            collectionBookRepo.upsert(membership("owned-col", "public-book"))
            collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
            collectionBookRepo.upsert(membership("stranger-col", "private-book"))
        }

        // An unscoped service is a wiring bug. It must deny rather than fall open — the same
        // choice GenreServiceImpl makes (absent principal collapses to the empty set).
        test("an absent principal counts nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.seedPublicAndPrivate()

                    f.service.getTagStats(TagId("t-noir")) shouldBe AppResult.Success(FacetStats.EMPTY)
                }
            }
        }

        // Fails before the fix: the aggregate counts every live book carrying the tag, so a member is
        // told "2 books" and shown one, and learns that a book they cannot reach exists.
        test("member getTagStats counts only the books they can reach; admin counts them all") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.seedPublicAndPrivate()
                    sql.booksQueries.updateTotalDuration(total_duration = 1_000L, id = "public-book")
                    sql.booksQueries.updateTotalDuration(total_duration = 2_000L, id = "private-book")

                    val member = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val admin = f.service.copyWith(principalFor("admin", UserRole.ADMIN))

                    member.getTagStats(TagId("t-noir")) shouldBe
                        AppResult.Success(FacetStats(bookCount = 1, totalDurationMs = 1_000L))
                    admin.getTagStats(TagId("t-noir")) shouldBe
                        AppResult.Success(FacetStats(bookCount = 2, totalDurationMs = 3_000L))
                }
            }
        }
    })

private fun tagPayload(
    id: String,
    slug: String,
): Tag = Tag(id = id, name = slug, slug = slug, revision = 0L, updatedAt = 0L)

private fun junction(
    id: String,
    bookId: String,
    tagId: String,
): BookTagSyncPayload = BookTagSyncPayload(id = id, bookId = bookId, tagId = tagId, createdAt = 0L, revision = 0L)

private data class TagReadAccessFixture(
    val service: TagServiceImpl,
    val bookRepo: BookRepository,
    val bookTagRepo: BookTagRepository,
    val tagRepo: TagRepository,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
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
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
