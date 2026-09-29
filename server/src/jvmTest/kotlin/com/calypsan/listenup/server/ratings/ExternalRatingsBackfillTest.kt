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
import com.calypsan.listenup.server.metadata.spi.RatingSourceAvailability
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Tests for [ExternalRatingsBackfill] — the catch-up that fetches every live book from each runnable
 * source that has never attempted it, instead of waiting for the nightly sweep's ceil(n/30) rotation
 * to reach it.
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

        /**
         * Two ASIN-bearing books ("book1", "book2") and a backfill over [sources], all real but the
         * sources themselves.
         */
        fun backfillTest(
            vararg sources: RecordingRatingSource,
            block: suspend TestScope.(
                List<RecordingRatingSource>,
                BookExternalRatingRepository,
                RecordingFetcher,
                ExternalRatingsBackfill,
            ) -> Unit,
        ) = withSqlDatabase {
            sql.seedTestLibraryAndFolder()
            sql.seedTestBook("book1", asin = "B001")
            sql.seedTestBook("book2", asin = "B002")
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
            val fetcher =
                RecordingFetcher(
                    registry = MetadataProviderRegistry(sources.toList()),
                    ratings = ratings,
                    sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED)),
                    books = sql.bookRepo(bus, registry, driver),
                    clock = FixedClock(now),
                )
            runTest {
                val backfill = ExternalRatingsBackfill(fetcher, ratings, scope = this, clock = FixedClock(now))
                block(sources.toList(), ratings, fetcher, backfill)
            }
        }

        test(
            "run fetches every book a runnable source never attempted, ASIN or not, skips attempted books, and survives one throwing fetch",
        ) {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("a-throws", asin = "A-THROWS")
                sql.seedTestBook("b-ok", asin = "B-OK")
                sql.seedTestBook("c-noasin") // no ASIN — Hardcover can still rate it
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
                            sources: Set<ExternalRatingSource>,
                        ): Outcome {
                            if (bookId.value == "a-throws") error("boom")
                            return super.fetch(bookId, locale, refresh, sources)
                        }
                    }

                runTest {
                    ratings.recordAttempt("d-attempted", ExternalRatingSource.AUDIBLE, now.toEpochMilliseconds())
                    val backfill =
                        ExternalRatingsBackfill(
                            fetcher = fetcher,
                            ratings = ratings,
                            scope = this,
                            clock = FixedClock(now),
                        )

                    backfill.run()

                    // Only the live, never-attempted books that don't throw ever reach the source.
                    audible.calledBooks shouldBe listOf("b-ok", "c-noasin")
                    // The throwing book still ends up "attempted" — otherwise every later pass would
                    // offer it to the same source again.
                    ratings.booksMissingAttempt(setOf(ExternalRatingSource.AUDIBLE), after = "", limit = 10) shouldBe
                        emptyList()
                }
            }
        }

        test("a book Audible already tried is fetched from Hardcover, and only from Hardcover") {
            backfillTest(
                RecordingRatingSource(),
                RecordingRatingSource(MetadataProviderId("hardcover"), ExternalRatingSource.HARDCOVER),
            ) { (audible, hardcover), ratings, fetcher, backfill ->
                ratings.recordAttempt("book1", ExternalRatingSource.AUDIBLE, now.toEpochMilliseconds())

                backfill.run()

                fetcher.fetched shouldBe
                    listOf(
                        "book1" to setOf(ExternalRatingSource.HARDCOVER),
                        "book2" to setOf(ExternalRatingSource.AUDIBLE, ExternalRatingSource.HARDCOVER),
                    )
                audible.calledBooks shouldBe listOf("book2")
                hardcover.calledBooks shouldBe listOf("book1", "book2")
                ratings.attemptedSources("book1") shouldBe
                    setOf(ExternalRatingSource.AUDIBLE, ExternalRatingSource.HARDCOVER)
            }
        }

        test("an unavailable Hardcover makes no book a candidate") {
            val hardcover = RecordingRatingSource(MetadataProviderId("hardcover"), ExternalRatingSource.HARDCOVER, available = false)
            backfillTest(RecordingRatingSource(), hardcover) { (audible), ratings, fetcher, backfill ->
                ratings.recordAttempt("book1", ExternalRatingSource.AUDIBLE, now.toEpochMilliseconds())
                ratings.recordAttempt("book2", ExternalRatingSource.AUDIBLE, now.toEpochMilliseconds())

                ratings.booksMissingAttempt(fetcher.runnableSources(), after = "", limit = 10) shouldBe emptyList()
                backfill.run()

                fetcher.fetched shouldBe emptyList()
                audible.calledBooks shouldBe emptyList()
                hardcover.calls shouldBe 0
            }
        }

        test("once Hardcover becomes available, the next pass fetches every book it never tried, from it alone") {
            val hardcover = RecordingRatingSource(MetadataProviderId("hardcover"), ExternalRatingSource.HARDCOVER, available = false)
            backfillTest(RecordingRatingSource(), hardcover) { (audible), _, fetcher, backfill ->
                backfill.run()
                audible.calledBooks shouldBe listOf("book1", "book2")

                hardcover.available = true
                fetcher.fetched.clear()
                backfill.run()

                fetcher.fetched shouldBe
                    listOf(
                        "book1" to setOf(ExternalRatingSource.HARDCOVER),
                        "book2" to setOf(ExternalRatingSource.HARDCOVER),
                    )
                hardcover.calledBooks shouldBe listOf("book1", "book2")
                audible.calledBooks shouldBe listOf("book1", "book2")
            }
        }

        test("a pass ends when no source can run") {
            backfillTest(RecordingRatingSource(available = false)) { (audible), _, _, backfill ->
                // runTest's own real-time timeout fails this if the pass never ends.
                backfill.run()

                audible.calls shouldBe 0
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
                            sources: Set<ExternalRatingSource>,
                        ): Outcome {
                            val outcome = super.fetch(bookId, locale, refresh, sources)
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

                    audible.calledBooks shouldBe listOf("book1", "book2")
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

                    audible.calledBooks shouldBe listOf("book1", "book2", "book3")
                }
            }
        }

        test("any signal on a subscribed flow — a Hardcover connection, a re-enabled source — triggers the backfill") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val ratings = BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val fetchedSignal = CompletableDeferred<Unit>()
                val hardcover =
                    RecordingRatingSource(
                        MetadataProviderId("hardcover"),
                        ExternalRatingSource.HARDCOVER,
                        onCalled = fetchedSignal,
                    )
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(hardcover)),
                        ratings = ratings,
                        sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED)),
                        books = sql.bookRepo(bus, registry, driver),
                        clock = FixedClock(now),
                    )
                // Real dispatchers, for the reason the scan-completion test below gives.
                val scope = CoroutineScope(SupervisorJob())
                try {
                    runBlocking {
                        val backfill = ExternalRatingsBackfill(fetcher, ratings, scope = scope, clock = FixedClock(now))
                        val connections = MutableSharedFlow<String>(extraBufferCapacity = 1)
                        scope.triggerExternalRatingsBackfillOn(connections, backfill)
                        connections.subscriptionCount.first { it > 0 }

                        connections.emit("user-1")
                        withTimeout(5.seconds) { fetchedSignal.await() }

                        hardcover.calledBooks shouldBe listOf("book1")
                    }
                } finally {
                    scope.cancel()
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

                        audible.calledBooks shouldBe listOf("book1")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    })

/** A real [ExternalRatingsFetcher] that also records each book it was asked to fetch, and from which sources. */
private class RecordingFetcher(
    registry: MetadataProviderRegistry,
    ratings: BookExternalRatingRepository,
    sourceSettings: RatingSourceSettings,
    books: BookRepository,
    clock: FixedClock,
) : ExternalRatingsFetcher(registry, ratings, sourceSettings, books, clock) {
    val fetched = mutableListOf<Pair<String, Set<ExternalRatingSource>>>()

    override suspend fun fetch(
        bookId: BookId,
        locale: MetadataLocale,
        refresh: Boolean,
        sources: Set<ExternalRatingSource>,
    ): Outcome {
        fetched += bookId.value to sources
        return super.fetch(bookId, locale, refresh, sources)
    }
}

/**
 * Minimal hand-rolled [RatingSource] fake that records every book it was asked to rate (by the id
 * `seedTestBook` puts in the title), and whose availability a test can switch.
 */
private class RecordingRatingSource(
    override val id: MetadataProviderId = MetadataProviderId.AUDIBLE,
    override val ratingSource: ExternalRatingSource = ExternalRatingSource.AUDIBLE,
    var available: Boolean = true,
    private val result: AppResult<ExternalRatingMeta?> = AppResult.Success(null),
    /**
     * Completed the first time [getRating] is called — lets a test await the effect of a
     * fire-and-forget trigger (e.g. [ExternalRatingsBackfill.trigger]) deterministically, without
     * pumping a [kotlinx.coroutines.test.TestCoroutineScheduler] across a real dispatcher hop
     * (`suspendTransaction`'s `withContext(sqlIoDispatcher)`).
     */
    private val onCalled: CompletableDeferred<Unit>? = null,
) : RatingSource {
    val calledBooks = mutableListOf<String>()

    val calls get() = calledBooks.size

    override suspend fun availability(): RatingSourceAvailability =
        if (available) {
            RatingSourceAvailability.Available
        } else {
            RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NO_CONNECTION)
        }

    override suspend fun getRating(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ExternalRatingMeta?> {
        calledBooks += book.title.removePrefix("Test Book ")
        onCalled?.complete(Unit)
        return result
    }
}
