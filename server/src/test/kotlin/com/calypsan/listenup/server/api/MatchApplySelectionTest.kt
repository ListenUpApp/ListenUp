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
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.provider.MetadataProvider
import com.calypsan.listenup.server.metadata.provider.MetadataSource
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

// Minimal valid 1×1 PNG (passes ImageStore's magic-number sniff).
private val ONE_PX_PNG: ByteArray =
    java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

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
            db: org.jetbrains.exposed.v1.jdbc.Database,
            genreRepo: GenreRepository,
            books: BookRepository,
            contributors: ContributorRepository,
            series: SeriesRepository,
            provider: MetadataProvider,
            coverBytes: ByteArray? = null,
            ladders: List<List<String>> = emptyList(),
        ): BookMetadataApplier {
            val tempDir = Files.createTempDirectory("matchapply-").also { it.toFile().deleteOnExit() }
            val engine =
                MockEngine {
                    if (coverBytes != null) {
                        respond(coverBytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()))
                    } else {
                        respond("", HttpStatusCode.NotFound)
                    }
                }
            return BookMetadataApplier(
                bookRepository = books,
                contributorRepository = contributors,
                seriesRepository = series,
                imageStorage = ImageStorage(httpClient = HttpClient(engine)),
                coverImageStore = CoverImageStore(ImageStore(tempDir.resolve("covers"), MAX_COVER_BYTES)),
                metadataProvider = provider,
                genreHierarchy = GenreHierarchyFromLadder(db, genreRepo, GenreAutoCreator(genreRepo)),
                ladderSource = { _, _ -> ladders },
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
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()))

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
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()))
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
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()))
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
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()))
                    a
                        .apply(BookId("nope"), "B0NEW", AudibleRegion.US, allButCover())
                        .shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("cover=on with a chosen coverUrl stores that cover as UPLOADED, overriding an existing cover") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    // give the book a pre-existing non-ENRICHED cover to prove the gate is gone
                    books.setManagedCover(BookId("b1"), "covers/old.png", "oldhash", CoverSource.EMBEDDED)

                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()), coverBytes = ONE_PX_PNG)
                    val sel = allButCover().copy(cover = true, coverUrl = "https://itunes/chosen.png")
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.cover.shouldNotBeNull()
                    saved.cover!!.source shouldBe CoverSource.UPLOADED
                }
            }
        }

        test("applies selected genres to the book, resolved through the cascade") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    suspendTransaction(db) { seedGenre("g-fant", "Fantasy", "fantasy", "/fantasy") }
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()))

                    val sel = allButCover().copy(genres = setOf("Fantasy"))
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.genres.map { it.name } shouldContainExactly listOf("Fantasy")
                }
            }
        }

        test("empty genres selection leaves the book's existing genres untouched") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    suspendTransaction(db) { seedGenre("g-fant", "Fantasy", "fantasy", "/fantasy") }
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    // seed the existing genre link AFTER the book row exists (FK constraint)
                    suspendTransaction(db) { BookGenreTable.insertIfAbsent("b1", "g-fant") }
                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()))

                    a
                        .apply(BookId("b1"), "B0NEW", AudibleRegion.US, allButCover().copy(genres = emptySet()))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    books.findById(BookId("b1"))!!.genres.map { it.name } shouldContainExactly listOf("Fantasy")
                }
            }
        }

        test("applying a match links only the selected genres while building the full taxonomy tree") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a =
                        applier(
                            db,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            fakeProvider(matchBook()),
                            ladders = listOf(listOf("Fiction", "Fantasy", "LitRPG")),
                        )

                    // The user selected only "Fiction"; "Fantasy"/"LitRPG" were deselected.
                    val sel = allButCover().copy(genres = setOf("Fiction"))
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    // Only the selected genre is linked to the book — deselected rungs are NOT re-added.
                    val saved = books.findById(BookId("b1"))!!
                    saved.genres.map { it.name }.toSet() shouldBe setOf("Fiction")

                    // … yet the taxonomy is still built/nested for the full ladder (depth 2, full path).
                    val leaf = genreRepo.findBySlug("litrpg")!!
                    leaf.depth shouldBe 2
                    leaf.path shouldBe "/fiction/fantasy/litrpg"
                }
            }
        }

        test("selecting every rung of a ladder links the book to all of them, nested") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a =
                        applier(
                            db,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            fakeProvider(matchBook()),
                            ladders = listOf(listOf("Fiction", "Fantasy", "LitRPG")),
                        )

                    val sel = allButCover().copy(genres = setOf("Fiction", "Fantasy", "LitRPG"))
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.genres.map { it.name }.toSet() shouldBe setOf("Fiction", "Fantasy", "LitRPG")

                    val leaf = genreRepo.findBySlug("litrpg")!!
                    leaf.depth shouldBe 2
                    leaf.path shouldBe "/fiction/fantasy/litrpg"
                }
            }
        }

        test("selecting only the leaf links just the leaf while still building its ancestor taxonomy") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a =
                        applier(
                            db,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            fakeProvider(matchBook()),
                            ladders = listOf(listOf("Fiction", "Fantasy", "LitRPG")),
                        )

                    val sel = allButCover().copy(genres = setOf("LitRPG"))
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    // Only the leaf is linked even though its ancestors weren't selected …
                    val saved = books.findById(BookId("b1"))!!
                    saved.genres.map { it.name }.toSet() shouldBe setOf("LitRPG")

                    // … and the ancestor taxonomy is still built, nesting the leaf at depth 2.
                    val leaf = genreRepo.findBySlug("litrpg")!!
                    leaf.depth shouldBe 2
                    leaf.path shouldBe "/fiction/fantasy/litrpg"
                }
            }
        }

        test("non-empty but unresolvable authorAsins leaves existing authors untouched") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db, bus, registry)
                val series = SeriesRepository(db, bus, registry)
                val genreRepo = GenreRepository(db, bus, registry)
                val books = BookRepository(db, bus, registry, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(db, genreRepo, books, contributors, series, fakeProvider(matchBook()))
                    // matchBook authors have asin "AUTH1"; select an asin that isn't in the match
                    val sel = allButCover().copy(authorAsins = setOf("NOT-IN-MATCH"), seriesAsins = setOf("NOT-IN-MATCH"))
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.contributors
                        .filter { it.role.equals("author", ignoreCase = true) }
                        .single()
                        .name shouldBe "Old Author"
                }
            }
        }
    })

@Suppress("LongParameterList")
private fun seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
    sortOrder: Int = 0,
    deletedAt: Long? = null,
) {
    GenreTable.insert {
        it[GenreTable.id] = id
        it[GenreTable.name] = name
        it[GenreTable.slug] = slug
        it[GenreTable.path] = path
        it[GenreTable.parentId] = parentId
        it[GenreTable.depth] = depth
        it[GenreTable.sortOrder] = sortOrder
        it[GenreTable.color] = null
        it[GenreTable.description] = null
        it[GenreTable.revision] = 0L
        it[GenreTable.createdAt] = 0L
        it[GenreTable.updatedAt] = 0L
        it[GenreTable.deletedAt] = deletedAt
        it[GenreTable.clientOpId] = null
    }
}
