@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.ratings.ExternalRatingsFetcher
import com.calypsan.listenup.server.ratings.RatingSourceSettings
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path as IoPath

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

/**
 * Covers [BookMetadataApplier]'s "on match" trigger for outside ratings: a real
 * [ExternalRatingsFetcher] wired through the `externalRatingsFetch` lambda, run right after a
 * successful apply. See `ExternalRatingsFetcherTest` for the fetcher's own behaviour and
 * `ExternalRatingsSweepTaskTest` / `BookRatingServiceImplTest` for the other two triggers.
 */
class BookMetadataApplierExternalRatingsTest :
    FunSpec({
        fun seedBook(id: String) =
            BookSyncPayload(
                id = id,
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Old Title",
                sortTitle = null,
                subtitle = null,
                description = null,
                publishYear = null,
                publisher = null,
                language = null,
                isbn = null,
                asin = null,
                abridged = false,
                explicit = false,
                hasScanWarning = false,
                totalDuration = 0L,
                cover = null,
                rootRelPath = "test/$id",
                inode = null,
                scannedAt = 0L,
                contributors = emptyList(),
                series = emptyList(),
                audioFiles = emptyList(),
                chapters = emptyList(),
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        fun matchBook() =
            MetadataBook(
                asin = "B0NEW",
                title = "New Title",
                subtitle = null,
                description = null,
                publisher = null,
                releaseDate = null,
                runtimeMinutes = 600,
                language = null,
                authors = emptyList(),
                narrators = emptyList(),
                series = emptyList(),
                genres = emptyList(),
                coverUrl = null,
                coverUrlMaxSize = null,
            )

        fun basicSelection() =
            MetadataApplySelection(
                title = true,
                subtitle = false,
                description = false,
                publisher = false,
                releaseDate = false,
                language = false,
                cover = false,
                authorAsins = emptySet(),
                narratorAsins = emptySet(),
                seriesAsins = emptySet(),
            )

        fun applier(
            dbs: SqlTestDatabases,
            genreRepo: GenreRepository,
            books: BookRepository,
            contributors: ContributorRepository,
            series: SeriesRepository,
            match: MetadataBook,
            externalRatingsFetch: (suspend (BookId, MetadataLocale) -> Unit)?,
        ): BookMetadataApplier {
            val tempDir = Files.createTempDirectory("matchapply-ratings-").also { it.toFile().deleteOnExit() }
            val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
            return BookMetadataApplier(
                bookRepository = books,
                contributorRepository = contributors,
                seriesRepository = series,
                imageStorage = ImageStorage(httpClient = HttpClient(engine)),
                coverImageStore = CoverImageStore(ImageStore(IoPath(tempDir.resolve("covers").toString()), MAX_COVER_BYTES)),
                matchSource = { _, _ -> AppResult.Success(MetadataMatch(match, emptyMap())) },
                appliedBy = "test-user",
                genreHierarchy = GenreHierarchyFromLadder(dbs.sql, genreRepo, GenreAutoCreator(genreRepo)),
                sqlDb = dbs.sql,
                ladderSource = { _, _ -> emptyList() },
                enrichmentDeps = testEnrichmentDeps(dbs.sql, dbs.driver, ChangeBus(), SyncRegistry()),
                externalRatingsFetch = externalRatingsFetch,
            )
        }

        test("applying a match stores the Audible rating from the fixture") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                val externalRatings =
                    BookExternalRatingRepository(db = sql, bus = bus, registry = registry, driver = driver)
                val sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.CLOSED))
                // The AudibleProviderTest fixture's rating: rating = 4.8f, ratingCount = 100.
                val audible =
                    object : RatingSource {
                        override val id: MetadataProviderId = MetadataProviderId.AUDIBLE
                        override val ratingSource: ExternalRatingSource = ExternalRatingSource.AUDIBLE

                        override suspend fun getRating(
                            book: BookIdentity,
                            locale: MetadataLocale,
                            refresh: Boolean,
                        ): AppResult<ExternalRatingMeta?> = AppResult.Success(ExternalRatingMeta(4.8, 100))
                    }
                val fetcher =
                    ExternalRatingsFetcher(
                        registry = MetadataProviderRegistry(listOf(audible)),
                        ratings = externalRatings,
                        sourceSettings = sourceSettings,
                        books = books,
                    )

                runTest {
                    books.upsert(seedBook("b1"), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()

                    val a =
                        applier(
                            this@withSqlDatabase,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            matchBook(),
                            externalRatingsFetch = { id, locale -> fetcher.fetch(id, locale, refresh = true) },
                        )

                    a
                        .apply(BookId("b1"), "B0NEW", MetadataLocale("us"), basicSelection())
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val row = externalRatings.findForBook("b1").single()
                    row.average shouldBe 4.8
                    row.count shouldBe 100
                }
            }
        }

        test("a fetcher that throws doesn't fail the apply") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)

                runTest {
                    books.upsert(seedBook("b1"), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()

                    val a =
                        applier(
                            this@withSqlDatabase,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            matchBook(),
                            externalRatingsFetch = { _, _ -> error("boom") },
                        )

                    val result = a.apply(BookId("b1"), "B0NEW", MetadataLocale("us"), basicSelection())

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    books.findById(BookId("b1"))!!.title shouldBe "New Title"
                }
            }
        }

        test("a null externalRatingsFetch is a no-op — the apply still succeeds") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)

                runTest {
                    books.upsert(seedBook("b1"), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()

                    val a =
                        applier(
                            this@withSqlDatabase,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            matchBook(),
                            externalRatingsFetch = null,
                        )

                    a
                        .apply(BookId("b1"), "B0NEW", MetadataLocale("us"), basicSelection())
                        .shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }
    })
