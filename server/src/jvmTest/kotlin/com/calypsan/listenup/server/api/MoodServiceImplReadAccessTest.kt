@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.MoodError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.MoodRepository
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
 * Access-gate tests for [MoodServiceImpl]'s READ surface.
 *
 * `BookAccessPolicy`'s own KDoc states the invariant: it is "the single source of truth for
 * book-level visibility… every seam that scopes books to a viewer derives from this one definition
 * rather than re-deriving the rule, so the access boundary can never drift between surfaces."
 * Mood reads were the seam that drifted — they consulted the junction tables directly, so a member
 * could enumerate the ids of books in collections they cannot reach, and read the moods of a book
 * [BookServiceImpl.getBook] would have reported as absent.
 *
 * Every assertion carries a **visible control** — a book the member legitimately sees. Without it a
 * gate that returned nothing at all would pass, and the test would prove only that the query was
 * broken.
 */
class MoodServiceImplReadAccessTest :
    FunSpec({

        fun SqlTestDatabases.fixture(): MoodReadAccessFixture {
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
            val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry, driver = driver)
            val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
            return MoodReadAccessFixture(
                service =
                    MoodServiceImpl(
                        moodRepository = moodRepo,
                        bookMoodRepository = bookMoodRepo,
                        sql = sql,
                        accessPolicy = BookAccessPolicy(sql, driver),
                    ),
                bookRepo = bookRepo,
                bookMoodRepo = bookMoodRepo,
                moodRepo = moodRepo,
                collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver),
                collectionBookRepo =
                    CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver),
            )
        }

        /** Two books with the same mood: one the member can reach, one only a stranger can. */
        suspend fun MoodReadAccessFixture.seedPublicAndPrivate() {
            bookRepo.upsert(bookPayloadFixture("public-book", "Public"))
            bookRepo.upsert(bookPayloadFixture("private-book", "Private"))
            moodRepo.upsert(moodPayload("m-cosy", "cosy"))
            bookMoodRepo.upsert(junction("bm-public", "public-book", "m-cosy"))
            bookMoodRepo.upsert(junction("bm-private", "private-book", "m-cosy"))
            collectionRepo.upsert(privateCollection("owned-col", owner = "member"))
            collectionBookRepo.upsert(membership("owned-col", "public-book"))
            collectionRepo.upsert(privateCollection("stranger-col", owner = "stranger"))
            collectionBookRepo.upsert(membership("stranger-col", "private-book"))
        }

        // ⛔ Fails today: the junction rows are returned unfiltered, so the member learns the id of
        // a book sitting in a collection they have no relationship with.
        test("member listBooksForMood never enumerates a book they cannot reach") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.seedPublicAndPrivate()

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))
                    val result = scoped.listBooksForMood(MoodId("m-cosy"), limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data.map { it.value } shouldBe listOf("public-book")
                }
            }
        }

        test("admin listBooksForMood still sees every book — the bypass is preserved") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.seedPublicAndPrivate()

                    val scoped = f.service.copyWith(principalFor("admin", UserRole.ADMIN))
                    val result = scoped.listBooksForMood(MoodId("m-cosy"), limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data.map { it.value }.sorted() shouldBe listOf("private-book", "public-book")
                }
            }
        }

        // ⛔ Fails today: the gate is `bookExists`, so a denied book returns its moods rather than
        // the same NotFound an absent book would produce. BookServiceImpl.getBook is explicit that
        // a denied book must be indistinguishable from one that does not exist.
        test("member listMoodsForBook reports a denied book as absent, not as forbidden") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                val f = fixture()
                runTest {
                    f.seedPublicAndPrivate()

                    val scoped = f.service.copyWith(principalFor("member", UserRole.MEMBER))

                    // Control: the reachable book answers normally, so a blanket-deny would fail here.
                    val visible = scoped.listMoodsForBook(BookId("public-book"))
                    visible.shouldBeInstanceOf<AppResult.Success<*>>()

                    val denied = scoped.listMoodsForBook(BookId("private-book"))
                    val failure = denied.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<MoodError.BookNotFound>()
                }
            }
        }

        test("admin listMoodsForBook reads the private book's moods") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.seedPublicAndPrivate()

                    val scoped = f.service.copyWith(principalFor("admin", UserRole.ADMIN))
                    val result = scoped.listMoodsForBook(BookId("private-book"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<Mood>>>()
                    success.data.map { it.slug } shouldBe listOf("cosy")
                }
            }
        }

        // An unscoped service is a wiring bug. It must deny rather than fall open — the same
        // choice GenreServiceImpl makes (absent principal collapses to the empty set).
        test("an absent principal enumerates nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val f = fixture()
                runTest {
                    f.seedPublicAndPrivate()

                    val result = f.service.listBooksForMood(MoodId("m-cosy"), limit = 50)

                    result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>().data.shouldBeEmptyList()
                }
            }
        }
    })

private fun <T> List<T>.shouldBeEmptyList() {
    this shouldBe emptyList<T>()
}

private fun moodPayload(
    id: String,
    slug: String,
): Mood = Mood(id = id, name = slug, slug = slug, revision = 0L, updatedAt = 0L)

private fun junction(
    id: String,
    bookId: String,
    moodId: String,
): BookMoodSyncPayload = BookMoodSyncPayload(id = id, bookId = bookId, moodId = moodId, createdAt = 0L, revision = 0L)

private data class MoodReadAccessFixture(
    val service: MoodServiceImpl,
    val bookRepo: BookRepository,
    val bookMoodRepo: BookMoodRepository,
    val moodRepo: MoodRepository,
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
