@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.ratings

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.scanner.EmbeddedScanCounters
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Tests for [ExternalRatingsBackfill] — the one-time-per-book catch-up that fetches every live,
 * ASIN-bearing book [ExternalRatingsFetcher] has never once attempted, instead of waiting for the
 * nightly sweep's ceil(n/30) rotation to reach it.
 */
class ExternalRatingsBackfillTest :
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

        test(
            "run fetches every never-attempted book, skips attempted and ASIN-less books, and survives one throwing fetch",
        ) {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("a-throws", asin = "A-THROWS")
                sql.seedTestBook("b-ok", asin = "B-OK")
                sql.seedTestBook("c-noasin") // no ASIN — never a candidate
                sql.seedTestBook("d-attempted", asin = "D-ATT")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    RecordingRatingSource(result = AppResult.Success(ExternalRatingMeta(4.0, 1)))
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
                        ): Outcome {
                            if (bookId.value == "a-throws") error("boom")
                            return super.fetch(bookId, locale, refresh)
                        }
                    }

                runTest {
                    ratings.recordAttempt("d-attempted", now.toEpochMilliseconds())
                    val backfill =
                        ExternalRatingsBackfill(
                            fetcher = fetcher,
                            ratings = ratings,
                            scope = this,
                            clock = FixedClock(now),
                        )

                    backfill.run()

                    // Only the live, ASIN-bearing, never-attempted book that doesn't throw ever
                    // reaches the rating source.
                    audible.calledAsins shouldBe listOf("B-OK")
                    // The throwing book still ends up "attempted" — otherwise it would sort right
                    // back into the queue and spin the pass forever.
                    ratings.neverAttempted(limit = 10) shouldBe emptyList()
                }
            }
        }

        test("a book added during a pass is fetched by that same pass") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    RecordingRatingSource(result = AppResult.Success(ExternalRatingMeta(4.0, 1)))
                var addedMidPass = false
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
                        ): Outcome {
                            val outcome = super.fetch(bookId, locale, refresh)
                            // Simulates a scan that completes WHILE the pass is mid-flight, adding a
                            // second never-attempted, ASIN-bearing book.
                            if (bookId.value == "book1" && !addedMidPass) {
                                addedMidPass = true
                                sql.seedTestBook("book2", asin = "B002")
                            }
                            return outcome
                        }
                    }

                runTest {
                    val backfill =
                        ExternalRatingsBackfill(
                            fetcher = fetcher,
                            ratings = ratings,
                            scope = this,
                            clock = FixedClock(now),
                        )

                    backfill.run()

                    audible.calledAsins shouldBe listOf("B001", "B002")
                    ratings.neverAttempted(limit = 10) shouldBe emptyList()
                }
            }
        }

        test("two concurrent triggers fetch each book once") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                sql.seedTestBook("book2", asin = "B002")
                sql.seedTestBook("book3", asin = "B003")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val audible =
                    RecordingRatingSource(result = AppResult.Success(ExternalRatingMeta(4.0, 1)))
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                runTest {
                    val backfill =
                        ExternalRatingsBackfill(
                            fetcher = fetcher,
                            ratings = ratings,
                            scope = this,
                            clock = FixedClock(now),
                        )

                    val first = backfill.trigger()
                    val second = backfill.trigger()
                    first.join()
                    second.join()

                    audible.calledAsins shouldBe listOf("B001", "B002", "B003")
                }
            }
        }

        test("a completed scan triggers the backfill through the ScanEvent bus") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val books = sql.bookRepo(bus, registry, driver)
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val settings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                val fetchedSignal = CompletableDeferred<Unit>()
                val audible =
                    RecordingRatingSource(
                        result = AppResult.Success(ExternalRatingMeta(4.0, 1)),
                        onCalled = fetchedSignal,
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = ratings,
                        sourceSettings = settings,
                        books = books,
                        clock = FixedClock(now),
                    )

                // Real dispatchers, not runTest's virtual scheduler: the backfill's fetch hops onto a
                // real dispatcher (suspendTransaction's sqlIoDispatcher), and a separately-launched
                // collector coroutine resuming from that hop is not something runTest's
                // runCurrent()/advanceUntilIdle() can reliably pump — a genuine, boundedly-timed
                // wait is the deterministic way to observe a fire-and-forget trigger's effect.
                val scope = CoroutineScope(SupervisorJob())
                try {
                    runBlocking {
                        val backfill =
                            ExternalRatingsBackfill(
                                fetcher = fetcher,
                                ratings = ratings,
                                scope = scope,
                                clock = FixedClock(now),
                            )
                        val events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 1)
                        scope.triggerExternalRatingsBackfillOnScanCompletion(events, backfill)

                        events.emit(
                            ScanEvent.Completed(
                                correlationId = "scan-1",
                                libraryId = LibraryId("test-library"),
                                result =
                                    ScanResultSummary(
                                        correlationId = "scan-1",
                                        totalBooks = 1,
                                        added = 1,
                                        modified = 0,
                                        removed = 0,
                                        moved = 0,
                                        errors = 0,
                                        durationMs = 1,
                                        filesWalked = 1,
                                        persisted = 1,
                                        failed = 0,
                                        embedded = EmbeddedScanCounters(),
                                    ),
                            ),
                        )
                        withTimeout(5.seconds) { fetchedSignal.await() }

                        audible.calledAsins shouldBe listOf("B001")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    })

/** Minimal hand-rolled [RatingSource] fake that records every ASIN it was asked to rate. */
private class RecordingRatingSource(
    private val result: AppResult<ExternalRatingMeta?> = AppResult.Success(null),
    /**
     * Completed the first time [getRating] is called — lets a test await the effect of a
     * fire-and-forget trigger (e.g. [ExternalRatingsBackfill.trigger]) deterministically, without
     * pumping a [kotlinx.coroutines.test.TestCoroutineScheduler] across a real dispatcher hop
     * (`suspendTransaction`'s `withContext(sqlIoDispatcher)`).
     */
    private val onCalled: CompletableDeferred<Unit>? = null,
) : RatingSource {
    override val id: MetadataProviderId = MetadataProviderId.AUDIBLE
    override val ratingSource: ExternalRatingSource = ExternalRatingSource.AUDIBLE

    val calledAsins = mutableListOf<String>()

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        book.asin?.let { calledAsins += it }
        onCalled?.complete(Unit)
        return result
    }
}
