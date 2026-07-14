@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
import kotlinx.io.files.Path as IoPath

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

// Minimal valid 1×1 PNG (passes ImageStore's magic-number sniff).
private val ONE_PX_PNG: ByteArray =
    java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

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
            dbs: SqlTestDatabases,
            genreRepo: GenreRepository,
            books: BookRepository,
            contributors: ContributorRepository,
            series: SeriesRepository,
            match: MetadataBook,
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
                coverImageStore = CoverImageStore(ImageStore(IoPath(tempDir.resolve("covers").toString()), MAX_COVER_BYTES)),
                matchSource = { _, _ -> AppResult.Success(match) },
                enrichmentProvider = "audible",
                genreHierarchy = GenreHierarchyFromLadder(dbs.sql, genreRepo, GenreAutoCreator(genreRepo)),
                sqlDb = dbs.sql,
                ladderSource = { _, _ -> ladders },
                // Fresh bus/registry for the (independent, unasserted) mood/tag writer domains;
                // empty product-tag source keeps enrichment a no-op for this selection-focused suite.
                enrichmentDeps = testEnrichmentDeps(dbs.sql, ChangeBus(), SyncRegistry()),
            )
        }

        test("applies all selected scalar fields, contributors, series; parses release year") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())

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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())
                    a
                        .apply(BookId("nope"), "B0NEW", AudibleRegion.US, allButCover())
                        .shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("cover=on with a chosen coverUrl stores that cover as UPLOADED, overriding an existing cover") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    // give the book a pre-existing non-ENRICHED cover to prove the gate is gone
                    books.setManagedCover(BookId("b1"), "covers/old.png", "oldhash", CoverSource.EMBEDDED)

                    val a =
                        applier(
                            this@withSqlDatabase,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            matchBook(),
                            coverBytes = ONE_PX_PNG,
                        )
                    val sel = allButCover().copy(cover = true, coverUrl = "https://itunes/chosen.png")
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.cover.shouldNotBeNull()
                    saved.cover!!.source shouldBe CoverSource.UPLOADED
                }
            }
        }

        test("applies selected genres to the book, resolved through the cascade") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    sql.seedGenre("g-fant", "Fantasy", "fantasy", "/fantasy")
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())

                    val sel = allButCover().copy(genres = setOf("Fantasy"))
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = books.findById(BookId("b1"))!!
                    saved.genres.map { it.name } shouldContainExactly listOf("Fantasy")
                }
            }
        }

        test("empty genres selection removes the book's existing genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    sql.seedGenre("g-fant", "Fantasy", "fantasy", "/fantasy")
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    // seed the existing genre link AFTER the book row exists (FK constraint)
                    sql.bookGenresQueries.insertIfAbsent(book_id = "b1", genre_id = "g-fant")
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())

                    a
                        .apply(BookId("b1"), "B0NEW", AudibleRegion.US, allButCover().copy(genres = emptySet()))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    books.findById(BookId("b1"))!!.genres.shouldBeEmpty()
                }
            }
        }

        test("a matched book with a category ladder is linked to the nested leaf genre") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a =
                        applier(
                            this@withSqlDatabase,
                            genreRepo,
                            books,
                            contributors,
                            series,
                            matchBook(),
                            ladders = listOf(listOf("Fiction", "Fantasy", "LitRPG")),
                        )

                    // genres selection must be non-empty for the ladder path to engage.
                    val sel = allButCover().copy(genres = setOf("Fiction"))
                    a.apply(BookId("b1"), "B0NEW", AudibleRegion.US, sel).shouldBeInstanceOf<AppResult.Success<*>>()

                    // Every rung is linked …
                    val saved = books.findById(BookId("b1"))!!
                    saved.genres.map { it.name }.toSet() shouldBe setOf("Fiction", "Fantasy", "LitRPG")

                    // … and the leaf is nested under the ladder (depth 2, full materialized path).
                    val leaf = genreRepo.findBySlug("litrpg")!!
                    leaf.depth shouldBe 2
                    leaf.path shouldBe "/fiction/fantasy/litrpg"
                }
            }
        }

        test("enriched publisher/language/publishYear/genres survive a later rescan (A7)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    sql.seedGenre("g-fant", "Fantasy", "fantasy", "/fantasy")
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())

                    // Apply provider enrichment: publisher/language/publishYear + Fantasy genre.
                    a
                        .apply(BookId("b1"), "B0NEW", AudibleRegion.US, allButCover().copy(genres = setOf("Fantasy")))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    // Rescan the same book (natural-key hit on folder+rootRelPath) with DIFFERENT
                    // file-derived scalars and genres. Enriched provenance must win.
                    books.resolveOrInsert(
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        rescanFor(
                            rootRelPath = "test/b1",
                            publisher = "File Publisher",
                            language = "german",
                            publishYear = 1900,
                            genres = listOf("Horror"),
                        ),
                    )

                    val saved = books.findById(BookId("b1"))!!
                    saved.publisher shouldBe "New Publisher"
                    saved.language shouldBe "english"
                    saved.publishYear shouldBe 2015
                    saved.genres.map { it.name } shouldContainExactly listOf("Fantasy")
                }
            }
        }

        test("non-empty but unresolvable authorAsins leaves existing authors untouched") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(sql, bus, registry)
                val series = SeriesRepository(sql, bus, registry)
                val genreRepo = GenreRepository(sql, bus, registry)
                val books = BookRepository(sql, bus, registry, driver, contributors, series, genreRepo)
                runTest {
                    val oldAuthorId = contributors.resolveOrCreate("Old Author", sortName = null).value
                    books.upsert(seedBook("b1", oldAuthorId), clientOpId = null).shouldBeInstanceOf<AppResult.Success<*>>()
                    val a = applier(this@withSqlDatabase, genreRepo, books, contributors, series, matchBook())
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

/**
 * A minimal file-derived [AnalyzedBook] for a rescan of an existing book at [rootRelPath], with
 * scalar/genre values that deliberately differ from any enrichment already applied — so a test can
 * assert the enriched values survive the scan (A7).
 */
private fun rescanFor(
    rootRelPath: String,
    publisher: String?,
    language: String?,
    publishYear: Int?,
    genres: List<String>,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = rootRelPath.hashCode().toLong(),
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = "New Title",
        publisher = publisher,
        language = language,
        publishedYear = publishYear,
        genres = genres,
        tracks = listOf(TrackEntry(file = file)),
    )
}

@Suppress("LongParameterList")
private fun ListenUpDatabase.seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
    sortOrder: Int = 0,
    deletedAt: Long? = null,
) {
    genresQueries.insert(
        id = id,
        name = name,
        slug = slug,
        path = path,
        parent_id = parentId,
        depth = depth.toLong(),
        sort_order = sortOrder.toLong(),
        color = null,
        description = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = deletedAt,
        client_op_id = null,
    )
}
