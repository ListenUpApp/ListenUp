@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.ratings

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.metadata.spi.RatingSourceAvailability
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import kotlin.time.Duration.Companion.days
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ExternalRatingsFetcher] — runs every enabled [RatingSource] for one book, recording
 * each independently so one source failing never stops another.
 */
class ExternalRatingsFetcherTest :
    FunSpec({
        val now = Instant.parse("2026-05-24T12:00:00Z")

        fun ListenUpDatabase.bookRepo(
            bus: ChangeBus,
            registry: SyncRegistry,
            driver: SqlDriver,
        ): BookRepository =
            BookRepository(
                db = this,
                bus = bus,
                registry = registry,
                driver = driver,
                contributorRepository = ContributorRepository(this, bus, registry),
                seriesRepository = SeriesRepository(this, bus, registry),
                genreRepository = GenreRepository(this, bus, registry),
            )

        test("two sources, one fails: the other's row is written and the failing one's error is recorded") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    FakeRatingSource(
                        MetadataProviderId.AUDIBLE,
                        ExternalRatingSource.AUDIBLE,
                        result = AppResult.Success(ExternalRatingMeta(4.5, 200)),
                    )
                val hardcover =
                    FakeRatingSource(
                        MetadataProviderId("hardcover"),
                        ExternalRatingSource.HARDCOVER,
                        result = AppResult.Failure(MetadataError.ExternalUnavailable()),
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible, hardcover)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    val outcome = fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)

                    outcome shouldBe ExternalRatingsFetcher.Outcome(tried = 2, answered = 1)
                    val audibleRow = ratings.findForBook("book1").single { it.source == ExternalRatingSource.AUDIBLE }
                    audibleRow.average shouldBe 4.5
                    audibleRow.count shouldBe 200

                    val (_, error) = settings.health(ExternalRatingSource.HARDCOVER)
                    error shouldBe "Couldn't reach the external metadata service. Try again later."
                }
            }
        }

        test("a disabled source is never called") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    FakeRatingSource(
                        MetadataProviderId.AUDIBLE,
                        ExternalRatingSource.AUDIBLE,
                        result = AppResult.Success(ExternalRatingMeta(4.0, 10)),
                    )
                val goodreads =
                    FakeRatingSource(
                        MetadataProviderId("goodreads"),
                        ExternalRatingSource.GOODREADS,
                        result = AppResult.Success(ExternalRatingMeta(3.0, 5)),
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible, goodreads)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    settings.setEnabled(ExternalRatingSource.GOODREADS, enabled = false)

                    val outcome = fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)

                    goodreads.calls shouldBe 0
                    outcome shouldBe ExternalRatingsFetcher.Outcome(tried = 1, answered = 1)
                }
            }
        }

        test("Success(null) leaves an existing row alone") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    FakeRatingSource(MetadataProviderId.AUDIBLE, ExternalRatingSource.AUDIBLE, result = AppResult.Success(null))
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    ratings.recordFetch("book1", ExternalRatingSource.AUDIBLE, 4.0, 50, "us", fetchedAt = 500L)

                    val outcome = fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)

                    outcome shouldBe ExternalRatingsFetcher.Outcome(tried = 1, answered = 1)
                    val row = ratings.findForBook("book1").single { it.source == ExternalRatingSource.AUDIBLE }
                    row.average shouldBe 4.0
                    row.count shouldBe 50
                }
            }
        }

        test("Outcome counts every enabled source tried, and every source that answered") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val rated =
                    FakeRatingSource(
                        MetadataProviderId.AUDIBLE,
                        ExternalRatingSource.AUDIBLE,
                        result = AppResult.Success(ExternalRatingMeta(4.1, 9)),
                    )
                val confidentMiss =
                    FakeRatingSource(
                        MetadataProviderId("goodreads"),
                        ExternalRatingSource.GOODREADS,
                        result = AppResult.Success(null),
                    )
                val failed =
                    FakeRatingSource(
                        MetadataProviderId("hardcover"),
                        ExternalRatingSource.HARDCOVER,
                        result = AppResult.Failure(MetadataError.ExternalUnavailable()),
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(rated, confidentMiss, failed)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    val outcome = fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)

                    outcome shouldBe ExternalRatingsFetcher.Outcome(tried = 3, answered = 2)
                }
            }
        }

        test("a thrown exception is contained and recorded as that source's error") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val throwing =
                    FakeRatingSource(
                        MetadataProviderId.AUDIBLE,
                        ExternalRatingSource.AUDIBLE,
                        throwable = IllegalStateException("boom"),
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(throwing)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    val outcome = fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)

                    outcome shouldBe ExternalRatingsFetcher.Outcome(tried = 1, answered = 0)
                    val (_, error) = settings.health(ExternalRatingSource.AUDIBLE)
                    error shouldBe "boom"
                }
            }
        }

        test("a source that has never succeeded still reports its failure in health") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                // No book_external_ratings row for AUDIBLE has ever been written — this source has
                // never once succeeded, e.g. the wrong region or unreachable from day one.
                val audible =
                    FakeRatingSource(
                        MetadataProviderId.AUDIBLE,
                        ExternalRatingSource.AUDIBLE,
                        result = AppResult.Failure(MetadataError.ExternalUnavailable()),
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    ratings.findForBook("book1") shouldBe emptyList()

                    fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)

                    val (fetchedAt, error) = settings.health(ExternalRatingSource.AUDIBLE)
                    fetchedAt shouldBe null
                    error shouldBe "Couldn't reach the external metadata service. Try again later."
                }
            }
        }

        test("the fetcher stores the region a source answered from, not the region requested") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B071L4NKN4")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    FakeRatingSource(
                        MetadataProviderId.AUDIBLE,
                        ExternalRatingSource.AUDIBLE,
                        // Audible Canada answered even though the fetch was requested in "us".
                        result = AppResult.Success(ExternalRatingMeta(4.5, 63, region = "ca")),
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    fetcher.fetch(BookId("book1"), MetadataLocale(region = "us"), refresh = false)

                    ratings.regionForBook("book1") shouldBe "ca"
                }
            }
        }

        test("a permanently failing book does not starve the rest") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("failing", asin = "B-FAIL")
                sql.seedTestBook("never-touched", asin = "B-NEW")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val alwaysFails =
                    FakeRatingSource(
                        MetadataProviderId.AUDIBLE,
                        ExternalRatingSource.AUDIBLE,
                        result = AppResult.Failure(MetadataError.ExternalUnavailable()),
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(alwaysFails)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    // Without external_rating_attempts, "failing" would earn no book_external_ratings
                    // row and would sort first in the sweep FOREVER — even ahead of a book that has
                    // genuinely never been looked at once.
                    fetcher.fetch(BookId("failing"), MetadataLocale.DEFAULT, refresh = true)

                    ratings.findForBook("failing") shouldBe emptyList()
                    ratings.sweepCandidates(limit = 10) shouldBe listOf("never-touched", "failing")
                }
            }
        }

        test("a confident miss counts as a successful fetch for health") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    FakeRatingSource(MetadataProviderId.AUDIBLE, ExternalRatingSource.AUDIBLE, result = AppResult.Success(null))
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    val outcome = fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)

                    outcome shouldBe ExternalRatingsFetcher.Outcome(tried = 1, answered = 1)
                    val (fetchedAt, error) = settings.health(ExternalRatingSource.AUDIBLE)
                    fetchedAt shouldBe now.toEpochMilliseconds()
                    error shouldBe null
                }
            }
        }

        /** Wires a fetcher over [source] with real repos, and hands [block] the pieces. */
        suspend fun withFetcher(
            source: FakeRatingSource,
            block: suspend (ExternalRatingsFetcher, RatingSourceSettings, BookExternalRatingRepository) -> Unit,
        ) {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(source)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )
                runTest { block(fetcher, settings, ratings) }
            }
        }
        val nowMs = now.toEpochMilliseconds()

        test("an unavailable source is skipped: not tried, no failure recorded, never paused") {
            val src =
                FakeRatingSource(
                    MetadataProviderId("hardcover"),
                    ExternalRatingSource.HARDCOVER,
                    availability = RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NO_CONNECTION),
                )
            withFetcher(src) { fetcher, settings, _ ->
                repeat(6) {
                    fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false) shouldBe
                        ExternalRatingsFetcher.Outcome(tried = 0, answered = 0)
                }
                src.calls shouldBe 0
                settings.health(ExternalRatingSource.HARDCOVER).second shouldBe null
                settings.pausedUntil(ExternalRatingSource.HARDCOVER, nowMs) shouldBe null
            }
        }

        test("five consecutive failures pause a source for seven days, and a paused source is skipped") {
            val src =
                FakeRatingSource(
                    MetadataProviderId("hardcover"),
                    ExternalRatingSource.HARDCOVER,
                    result = AppResult.Failure(MetadataError.ExternalUnavailable()),
                )
            withFetcher(src) { fetcher, settings, _ ->
                repeat(5) { fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false) }
                src.calls shouldBe 5
                settings.pausedUntil(ExternalRatingSource.HARDCOVER, nowMs) shouldBe nowMs + 7.days.inWholeMilliseconds

                fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false) shouldBe
                    ExternalRatingsFetcher.Outcome(tried = 0, answered = 0)
                src.calls shouldBe 5
            }
        }

        test("a success resets the failure count") {
            withFetcher(FakeRatingSource(MetadataProviderId("hardcover"), ExternalRatingSource.HARDCOVER)) { _, settings, _ ->
                val s = ExternalRatingSource.HARDCOVER
                repeat(4) { settings.recordFailure(s, "boom", nowMs) }
                settings.recordSuccess(s, nowMs)
                repeat(4) { settings.recordFailure(s, "boom", nowMs) }
                settings.pausedUntil(s, nowMs) shouldBe null
            }
        }

        test("re-enabling a source clears its pause") {
            withFetcher(FakeRatingSource(MetadataProviderId("hardcover"), ExternalRatingSource.HARDCOVER)) { _, settings, _ ->
                val s = ExternalRatingSource.HARDCOVER
                repeat(5) { settings.recordFailure(s, "boom", nowMs) }
                settings.pausedUntil(s, nowMs) shouldBe nowMs + 7.days.inWholeMilliseconds
                settings.setEnabled(s, false)
                settings.setEnabled(s, true)
                settings.pausedUntil(s, nowMs) shouldBe null
                settings.recordFailure(s, "boom", nowMs)
                settings.pausedUntil(s, nowMs) shouldBe null
            }
        }

        test("a regionless source stores a null region") {
            val src =
                FakeRatingSource(
                    MetadataProviderId("goodreads"),
                    ExternalRatingSource.GOODREADS,
                    result = AppResult.Success(ExternalRatingMeta(4.2, 10)),
                )
            withFetcher(src) { fetcher, _, ratings ->
                fetcher.fetch(BookId("book1"), MetadataLocale.DEFAULT, refresh = false)
                ratings.findForBook("book1").size shouldBe 1
                ratings.regionForBook("book1") shouldBe null
            }
        }
    })

/** Minimal hand-rolled [RatingSource] fake — configure a result, or a [throwable] to simulate a fault. */
private class FakeRatingSource(
    override val id: MetadataProviderId,
    override val ratingSource: ExternalRatingSource,
    private val result: AppResult<ExternalRatingMeta?> = AppResult.Success(null),
    private val throwable: Throwable? = null,
    private val availability: RatingSourceAvailability = RatingSourceAvailability.Available,
) : RatingSource {
    var calls = 0
        private set

    override suspend fun availability(): RatingSourceAvailability = availability

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        calls++
        throwable?.let { throw it }
        return result
    }
}
