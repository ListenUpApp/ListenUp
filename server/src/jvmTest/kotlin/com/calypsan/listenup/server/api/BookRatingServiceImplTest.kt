@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.ExternalRatingsCheck
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.error.RatingError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.ratings.ExternalRatingsFetcher
import com.calypsan.listenup.server.ratings.HardcoverRatingOnOpen
import com.calypsan.listenup.server.ratings.RatingSourceSettings
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.sync.BookRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.makeBookAccessible
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class BookRatingServiceImplTest :
    FunSpec({
        fun principal(userId: String) = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("s"), UserRole.MEMBER) }

        fun adminPrincipal(userId: String) = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("s"), UserRole.ADMIN) }

        test("rating writes the caller's own row — the caller comes from the principal, never the request") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"))
                runTest {
                    service
                        .rate(BookId("b1"), RateBookRequest(candidateId = "c1", halfStars = 7, note = " Good. "))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row = repo.findForBook("b1").single()
                    row.userId shouldBe "u1"
                    row.halfStars shouldBe 7
                    row.note shouldBe "Good."
                }
            }
        }

        test("a book the caller cannot open answers NotFound and writes nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"))
                runTest {
                    val result = service.rate(BookId("b1"), RateBookRequest(candidateId = "c1", halfStars = 7, note = null))

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<SyncError.NotFound>()
                    repo.findForBook("b1") shouldBe emptyList()
                }
            }
        }

        test("clearing removes only the caller's rating, and clearing twice still succeeds") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u2")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val policy = BookAccessPolicy(sql, driver)
                runTest {
                    BookRatingServiceImpl(repo, policy, principal("u1")).rate(BookId("b1"), RateBookRequest("c1", 6, null))
                    BookRatingServiceImpl(repo, policy, principal("u2")).rate(BookId("b1"), RateBookRequest("c2", 9, null))

                    val u1 = BookRatingServiceImpl(repo, policy, principal("u1"))
                    u1.clearRating(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    u1.clearRating(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    repo.findForBook("b1").map { it.userId } shouldBe listOf("u2")
                }
            }
        }

        test("refreshExternalRatings is denied for a member") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1", asin = "B001")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val fetcher = fetcherOf(this@withSqlDatabase, result = AppResult.Success(ExternalRatingMeta(4.5, 100)))
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("member1"), fetcher)
                runTest {
                    val result = service.refreshExternalRatings(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("refreshExternalRatings succeeds for an admin and writes a row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1", asin = "B001")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val fetcher = fetcherOf(this@withSqlDatabase, result = AppResult.Success(ExternalRatingMeta(4.5, 100)))
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), adminPrincipal("admin1"), fetcher)
                runTest {
                    service.refreshExternalRatings(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row = externalRatings(this@withSqlDatabase).findForBook("b1").single()
                    row.average shouldBe 4.5
                    row.count shouldBe 100
                }
            }
        }

        test("refreshExternalRatings asks in the region the book's rating was last found in") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1", asin = "B001")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val seenLocales = mutableListOf<MetadataLocale>()
                val fetcher =
                    fetcherOf(this@withSqlDatabase, AppResult.Success(ExternalRatingMeta(4.5, 100)), seenLocales)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), adminPrincipal("admin1"), fetcher)
                runTest {
                    externalRatings(this@withSqlDatabase)
                        .recordFetch("b1", ExternalRatingSource.AUDIBLE, 4.1, 40, region = "uk", fetchedAt = 1L)

                    service.refreshExternalRatings(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    seenLocales shouldBe listOf(MetadataLocale("uk"))
                }
            }
        }

        test("refreshExternalRatings answers SourceUnavailable when every enabled source failed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1", asin = "B001")
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val fetcher =
                    fetcherOf(this@withSqlDatabase, result = AppResult.Failure(MetadataError.ExternalUnavailable()))
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), adminPrincipal("admin1"), fetcher)
                runTest {
                    val result = service.refreshExternalRatings(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<RatingError.SourceUnavailable>()
                }
            }
        }

        test("refreshExternalRatings answers NotFound for a book that does not exist") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val fetcher = fetcherOf(this@withSqlDatabase, result = AppResult.Success(ExternalRatingMeta(4.5, 100)))
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), adminPrincipal("admin1"), fetcher)
                runTest {
                    val result = service.refreshExternalRatings(BookId("missing"))

                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }

        test("ensureExternalRatings answers at once for a book the caller can open, and asks for its Hardcover rating") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val asked = mutableListOf<String>()
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = { null },
                        fetch = { asked += it.value },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service.ensureExternalRatings(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    asked shouldBe listOf("b1")
                }
            }
        }

        test("ensureExternalRatings for a book the caller cannot open answers NotFound and asks nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                val asked = mutableListOf<String>()
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = { null },
                        fetch = { asked += it.value },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service
                        .ensureExternalRatings(BookId("b1"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<SyncError.NotFound>()

                    asked shouldBe emptyList()
                }
            }
        }

        test("a failing fetch never fails the open") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = { null },
                        fetch = { throw IllegalStateException("hardcover down") },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service.ensureExternalRatings(BookId("b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                }
            }
        }

        test("checkExternalRatings says CHECKING while the on-open fetch runs, then DONE when it ends") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val gate = CompletableDeferred<Unit>()
                val asked = mutableListOf<String>()
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = { null },
                        fetch = { bookId ->
                            asked += bookId.value
                            gate.await()
                        },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service.checkExternalRatings(BookId("b1")).test {
                        awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.CHECKING)
                        expectNoEvents()
                        gate.complete(Unit)
                        awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.DONE)
                        awaitComplete()
                    }
                    asked shouldBe listOf("b1")
                }
            }
        }

        test("a second open of the same book follows the fetch already running, and starts no other") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val gate = CompletableDeferred<Unit>()
                var fetches = 0
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = { null },
                        fetch = {
                            fetches++
                            gate.await()
                        },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service.checkExternalRatings(BookId("b1")).test {
                        awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.CHECKING)
                        service.checkExternalRatings(BookId("b1")).test {
                            awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.CHECKING)
                            gate.complete(Unit)
                            awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.DONE)
                            awaitComplete()
                        }
                        awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.DONE)
                        awaitComplete()
                    }
                    fetches shouldBe 1
                }
            }
        }

        test("a fetch that outlasts the server's bound still ends the check") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val fetchScope = CoroutineScope(Dispatchers.Unconfined)
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = { null },
                        fetch = { awaitCancellation() },
                        scope = fetchScope,
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service.checkExternalRatings(BookId("b1")).test {
                        awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.CHECKING)
                        // Virtual time: Turbine collects on the test scheduler, so the 20 s bound passes
                        // without waiting for it.
                        awaitItem() shouldBe RpcEvent.Data(ExternalRatingsCheck.DONE)
                        awaitComplete()
                    }
                }
                fetchScope.cancel()
            }
        }

        test("a fresh rating needs no check: the stream ends without a word") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                makeBookAccessible(sql, driver, bookId = "b1", viewerId = "u1")
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = {
                            kotlin.time.Clock.System
                                .now()
                                .toEpochMilliseconds()
                        },
                        fetch = { error("a fresh rating is never fetched") },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service.checkExternalRatings(BookId("b1")).test { awaitComplete() }
                }
            }
        }

        test("a book the caller cannot open gets no check, and nothing is fetched") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")
                sql.seedTestUser("u1")
                val asked = mutableListOf<String>()
                val onOpen =
                    HardcoverRatingOnOpen(
                        lastTried = { null },
                        fetch = { asked += it.value },
                        scope = CoroutineScope(Dispatchers.Unconfined),
                    )
                val repo = BookRatingRepository(sql, ChangeBus(), SyncRegistry(), driver = driver)
                val service = BookRatingServiceImpl(repo, BookAccessPolicy(sql, driver), principal("u1"), onOpen = onOpen)
                runTest {
                    service.checkExternalRatings(BookId("b1")).test { awaitComplete() }
                    asked shouldBe emptyList()
                }
            }
        }
    })

/** A one-source [ExternalRatingsFetcher] over [db], always answering [result] from AUDIBLE. */
private fun fetcherOf(
    db: SqlTestDatabases,
    result: AppResult<ExternalRatingMeta?>,
    seenLocales: MutableList<MetadataLocale> = mutableListOf(),
): ExternalRatingsFetcher {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val books =
        BookRepository(
            db = db.sql,
            bus = bus,
            registry = registry,
            driver = db.driver,
            contributorRepository = ContributorRepository(db.sql, bus, registry),
            seriesRepository = SeriesRepository(db.sql, bus, registry),
            genreRepository = GenreRepository(db.sql, bus, registry),
        )
    val fake =
        object : RatingSource {
            override val id: MetadataProviderId = MetadataProviderId.AUDIBLE
            override val ratingSource: ExternalRatingSource = ExternalRatingSource.AUDIBLE

            override suspend fun getRating(
                book: BookIdentity,
                locale: MetadataLocale,
                refresh: Boolean,
            ): AppResult<ExternalRatingMeta?> {
                seenLocales += locale
                return result
            }
        }
    return ExternalRatingsFetcher(
        registry = MetadataProviderRegistry(listOf(fake)),
        ratings = externalRatings(db),
        sourceSettings = RatingSourceSettings(ServerSettingsRepository(db.sql, RegistrationPolicy.CLOSED)),
        books = books,
    )
}

private fun externalRatings(db: SqlTestDatabases): BookExternalRatingRepository =
    BookExternalRatingRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry(), driver = db.driver)
