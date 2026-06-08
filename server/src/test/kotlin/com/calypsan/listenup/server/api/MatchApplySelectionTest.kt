@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.provider.MetadataProvider
import com.calypsan.listenup.server.metadata.provider.MetadataSource
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

private fun fakeProvider(book: MetadataBook) =
    object : MetadataProvider {
        override val source = MetadataSource.AUDIBLE

        override suspend fun search(
            query: String,
            region: AudibleRegion?,
        ): AppResult<List<MetadataBook>> = AppResult.Success(listOf(book))

        override suspend fun getBook(
            id: String,
            region: AudibleRegion,
            refresh: Boolean,
        ): AppResult<MetadataBook?> = AppResult.Success(book)

        override suspend fun getChapters(
            id: String,
            region: AudibleRegion,
        ): AppResult<MetadataChapters?> = AppResult.Success(null)
    }

private fun matchBook() =
    MetadataBook(
        asin = "B0NEW",
        title = "New Title",
        subtitle = "New Subtitle",
        description = "New description.",
        publisher = "New Publisher",
        releaseDate = "2015-06-02",
        runtimeMinutes = 600,
        language = "english",
        authors = listOf(MetadataContributorRef(asin = "AUTH1", name = "New Author")),
        narrators = listOf(MetadataContributorRef(asin = "NARR1", name = "New Narrator")),
        series = listOf(MetadataSeriesRef(asin = "SER1", title = "New Series", sequence = "1")),
        genres = listOf("Fantasy"),
        coverUrl = "https://audible/new.jpg",
        coverUrlMaxSize = null,
    )

private fun allButCover() =
    MetadataApplySelection(
        title = true,
        subtitle = true,
        description = true,
        publisher = true,
        releaseDate = true,
        language = true,
        cover = false,
        authorAsins = setOf("AUTH1"),
        narratorAsins = setOf("NARR1"),
        seriesAsins = setOf("SER1"),
    )

class MatchApplySelectionTest :
    FunSpec({
        fun seedBook(
            id: String,
            authorId: String,
        ) = BookSyncPayload(
            id = id,
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Old Title",
            sortTitle = null,
            subtitle = "Old Subtitle",
            description = "Old description.",
            publishYear = 1999,
            publisher = "Old Publisher",
            language = "old",
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
            contributors =
                listOf(
                    BookContributorPayload(authorId, "Old Author", null, ContributorRole.AUTHOR.apiValue, null),
                ),
            series = emptyList(),
            audioFiles = emptyList(),
            chapters = emptyList(),
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )

        fun applier(
            books: BookRepository,
            contributors: ContributorRepository,
            series: SeriesRepository,
            provider: MetadataProvider,
        ): BookMetadataApplier {
            val tempDir = Files.createTempDirectory("matchapply-").also { it.toFile().deleteOnExit() }
            return BookMetadataApplier(
                bookRepository = books,
                contributorRepository = contributors,
                seriesRepository = series,
                imageStorage = ImageStorage(httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
                coverImageStore = CoverImageStore(ImageStore(tempDir.resolve("covers"), MAX_COVER_BYTES)),
                metadataProvider = provider,
            )
        }

        test("applies all selected scalar fields, contributors, series; parses release year") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(books, contributors, series, fakeProvider(matchBook()))

                    a
                        .apply(BookId("b1"), "B0NEW", AudibleRegion.US, allButCover())
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.title shouldBe "New Title"
                    saved.subtitle shouldBe "New Subtitle"
                    saved.description shouldBe "New description."
                    saved.publisher shouldBe "New Publisher"
                    saved.language shouldBe "english"
                    saved.publishYear shouldBe 2015
                    saved.asin shouldBe "B0NEW"
                    saved.contributors.map { it.name }.toSet() shouldBe setOf("New Author", "New Narrator")
                    saved.series.single().name shouldBe "New Series"
                    saved.cover.shouldBeNull() // cover deselected → no cover write
                }
            }
        }

        test("deselected scalar fields are left untouched") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(books, contributors, series, fakeProvider(matchBook()))
                    val sel = allButCover().copy(title = false, description = false, releaseDate = false)

                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.title shouldBe "Old Title"
                    saved.description shouldBe "Old description."
                    saved.publishYear shouldBe 1999
                    saved.publisher shouldBe "New Publisher" // still applied
                }
            }
        }

        test("empty author set leaves existing authors untouched") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(books, contributors, series, fakeProvider(matchBook()))
                    val sel = allButCover().copy(authorAsins = emptySet())

                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    val authors = saved.contributors.filter { it.role.equals("author", ignoreCase = true) }
                    authors.single().name shouldBe "Old Author" // untouched
                    saved.contributors.any { it.name == "New Narrator" } shouldBe true // narrators replaced
                }
            }
        }

        test("missing book is NotFound") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series)
                runTest {
                    val a = applier(books, contributors, series, fakeProvider(matchBook()))
                    a
                        .apply(BookId("nope"), "B0NEW", AudibleRegion.US, allButCover())
                        .shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }
    })
