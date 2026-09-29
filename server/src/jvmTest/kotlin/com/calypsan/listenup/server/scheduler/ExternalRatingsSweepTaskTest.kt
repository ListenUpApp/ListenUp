@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.ratings.ExternalRatingsBackfillRunner
import com.calypsan.listenup.server.ratings.ExternalRatingsFetcher
import com.calypsan.listenup.server.ratings.RatingSourceSettings
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
 * Tests for [ExternalRatingsSweepTask.runOnce] — the nightly 1/30th-of-the-library sweep. The
 * [start]/jitter/last-run machinery is identical in shape to [MetadataCacheCleanupTask], already
 * covered there; this file focuses on the sweep's own arithmetic and resilience.
 */
class ExternalRatingsSweepTaskTest :
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

        test("runOnce does nothing in an empty library") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible = RecordingRatingSource()
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )
                val task =
                    ExternalRatingsSweepTask(
                        fetcher = fetcher,
                        ratings = ratings,
                        backfill = ExternalRatingsBackfillRunner {},
                        clock = FixedClock(now),
                    )

                runTest {
                    task.runOnce() shouldBe 0
                    audible.calledAsins shouldBe emptyList()
                }
            }
        }

        test("the nightly quota counts books without an ASIN, and sweeps them") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                // 31 books, none with an ASIN — ceil(31 / 30) = 2. Hardcover can rate
                // a book by ISBN or title, so an ASIN-less book is as eligible as any other.
                (1..31).forEach { sql.seedTestBook("book%02d".format(it)) }
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible = RecordingRatingSource()
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )
                val task =
                    ExternalRatingsSweepTask(
                        fetcher = fetcher,
                        ratings = ratings,
                        backfill = ExternalRatingsBackfillRunner {},
                        clock = FixedClock(now),
                    )

                runTest {
                    task.runOnce() shouldBe 2
                    audible.calls shouldBe 2
                }
            }
        }

        test("runOnce refreshes exactly ceil(n/30) of the least-recently-touched ASIN'd books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                // 31 ASIN'd books, none ever fetched — ceil(31 / 30) = 2, never floor's 1.
                val ids = (1..31).map { "book%02d".format(it) }
                ids.forEach { id -> sql.seedTestBook(id, asin = "$id-asin") }
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible = RecordingRatingSource()
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )
                val task =
                    ExternalRatingsSweepTask(
                        fetcher = fetcher,
                        ratings = ratings,
                        backfill = ExternalRatingsBackfillRunner {},
                        clock = FixedClock(now),
                    )

                runTest {
                    val refreshed = task.runOnce()

                    refreshed shouldBe 2
                    audible.calledAsins shouldBe listOf("book01-asin", "book02-asin")
                }
            }
        }

        test("a failing fetch for one book doesn't stop the rest") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("boom", asin = "B-BOOM")
                sql.seedTestBook("fine", asin = "B-FINE")
                // 29 filler books, already fetched recently, so the sweep's 2 candidates (ceil(31/30))
                // are "boom" and "fine" — both never-touched, sorting ahead of the fillers.
                val fillerIds = (1..29).map { "filler%02d".format(it) }
                fillerIds.forEach { id -> sql.seedTestBook(id, asin = "$id-asin") }
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    RecordingRatingSource(result = AppResult.Success(ExternalRatingMeta(4.0, 10)))
                val flakyFetcher =
                    object : ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    ) {
                        override suspend fun fetch(
                            bookId: BookId,
                            locale: MetadataLocale,
                            refresh: Boolean,
                            sources: Set<ExternalRatingSource>,
                        ): Outcome {
                            if (bookId.value == "boom") error("boom")
                            return super.fetch(bookId, locale, refresh, sources)
                        }
                    }
                val task =
                    ExternalRatingsSweepTask(
                        fetcher = flakyFetcher,
                        ratings = ratings,
                        backfill = ExternalRatingsBackfillRunner {},
                        clock = FixedClock(now),
                    )

                runTest {
                    fillerIds.forEach { id ->
                        ratings.recordFetch(id, ExternalRatingSource.AUDIBLE, 4.0, 1, "us", fetchedAt = now.toEpochMilliseconds())
                    }

                    // "boom" sorts first (id ascending, both never-touched) — its fetch throws, but
                    // "fine" must still get its fetch.
                    val refreshed = task.runOnce()

                    refreshed shouldBe 2
                    audible.calledAsins shouldBe listOf("B-FINE")
                    ratings.findForBook("fine").single().average shouldBe 4.0
                }
            }
        }

        test("runOnce runs the backfill before its own refresh") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "book1-asin")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible = RecordingRatingSource()
                val calls = mutableListOf<String>()
                val fetcher =
                    object : ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    ) {
                        override suspend fun fetch(
                            bookId: BookId,
                            locale: MetadataLocale,
                            refresh: Boolean,
                            sources: Set<ExternalRatingSource>,
                        ): Outcome {
                            calls += "refresh:${bookId.value}"
                            return super.fetch(bookId, locale, refresh, sources)
                        }
                    }
                val backfill = ExternalRatingsBackfillRunner { calls += "backfill" }
                val task =
                    ExternalRatingsSweepTask(
                        fetcher = fetcher,
                        ratings = ratings,
                        backfill = backfill,
                        clock = FixedClock(now),
                    )

                runTest {
                    task.runOnce()

                    calls.first() shouldBe "backfill"
                    calls.drop(1) shouldBe listOf("refresh:book1")
                }
            }
        }
    })

/** Minimal hand-rolled [RatingSource] fake that records every ASIN it was asked to rate. */
private class RecordingRatingSource(
    private val result: AppResult<ExternalRatingMeta?> = AppResult.Success(null),
) : RatingSource {
    override val id: MetadataProviderId = MetadataProviderId.AUDIBLE
    override val ratingSource: ExternalRatingSource = ExternalRatingSource.AUDIBLE

    val calledAsins = mutableListOf<String>()

    var calls = 0
        private set

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        calls++
        book.asin?.let { calledAsins += it }
        return result
    }
}
