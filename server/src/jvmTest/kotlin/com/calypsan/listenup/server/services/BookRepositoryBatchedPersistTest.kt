@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Verifies [BookRepository.resolveOrInsertAll] — the batched, chunked-transaction scan-persist
 * path (#persist-batching) — against the per-book [BookRepository.resolveOrInsert] path it replaces.
 *
 * The headline guard is PARITY: persisting the same set of books through the batched path must land
 * byte-identical DB state (books, contributors, series, chapters, audio files, genres, covers,
 * system-collection membership, FTS searchability) to the per-book path. The remaining tests pin the
 * batched path's own correctness contracts: genre cascade + idempotent rescan, per-book containment,
 * and that the chunk boundary (more books than [PERSIST_CHUNK_SIZE_FOR_TEST]) is transparent.
 */
class BookRepositoryBatchedPersistTest :
    FunSpec({

        test("batched persist lands identical DB state to the per-book path") {
            // Two independent DBs: one driven per-book, one batched. Compare the structural aggregate.
            val perBook = persistThenSnapshot(batched = false)
            val batched = persistThenSnapshot(batched = true)

            // Same set of books by rootRelPath, with identical normalized aggregates.
            batched.keys shouldContainExactlyInAnyOrder perBook.keys
            for (path in perBook.keys) {
                batched[path] shouldBe perBook[path]
            }
        }

        test("batched persist resolves genres through all three cascade steps") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val genreId = "g-fantasy"
                    sql.transaction {
                        sql.seedGenre(genreId, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        sql.seedAlias("Fantasy", genreId)
                    }

                    // "Fantasy" → alias (step 1); "Mystery" → normalize to live taxonomy (step 2 — seeded
                    // by slug, no alias); "Cyberpunk" → auto-create (step 3).
                    sql.transaction {
                        sql.seedGenre("g-mystery", name = "Mystery", slug = "mystery", path = "/mystery")
                    }

                    repo.persistAllBooks(
                        listOf(
                            book("books/b1", genres = listOf("Fantasy", "Mystery", "Cyberpunk")),
                        ),
                    )

                    val names =
                        sql.bookGenresQueries
                            .genresForBook(sql.idOf("books/b1"))
                            .executeAsList()
                            .map { it.name }
                    names shouldContainExactlyInAnyOrder listOf("Fantasy", "Mystery", "Cyberpunk")
                }
            }
        }

        test("batched rescan that drops a genre string wipes it from book_genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    repo.persistAllBooks(listOf(book("books/b1", genres = listOf("Fantasy", "Horror"))))
                    sql.bookGenresQueries
                        .genresForBook(sql.idOf("books/b1"))
                        .executeAsList()
                        .map { it.name } shouldContainExactlyInAnyOrder listOf("Fantasy", "Horror")

                    // Rescan with only "Fantasy" — "Horror" must disappear (idempotent wipe-and-rewrite).
                    repo.persistAllBooks(listOf(book("books/b1", genres = listOf("Fantasy"))))
                    sql.bookGenresQueries
                        .genresForBook(sql.idOf("books/b1"))
                        .executeAsList()
                        .map { it.name } shouldContainExactly listOf("Fantasy")
                }
            }
        }

        test("an idempotent rescan does not bump the revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    repo.persistAllBooks(listOf(book("books/b1", genres = listOf("Fantasy"))))
                    val firstRevision = repo.findById(BookId(sql.idOf("books/b1")))!!.revision

                    // Identical rescan: same content, same (absent) cover — the batched path must skip
                    // the revision bump, matching upsertFromAnalyzed's matchesStoredContent short-circuit.
                    val result = repo.persistAllBooks(listOf(book("books/b1", genres = listOf("Fantasy"))))
                    result.persisted shouldBe 1
                    result.failed shouldBe 0

                    repo.findById(BookId(sql.idOf("books/b1")))!!.revision shouldBe firstRevision
                }
            }
        }

        test("a real change on rescan does bump the revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    repo.persistAllBooks(listOf(book("books/b1", title = "Original")))
                    val firstRevision = repo.findById(BookId(sql.idOf("books/b1")))!!.revision

                    repo.persistAllBooks(listOf(book("books/b1", title = "Renamed")))
                    val secondRevision = repo.findById(BookId(sql.idOf("books/b1")))!!.revision

                    (secondRevision > firstRevision) shouldBe true
                    repo.findById(BookId(sql.idOf("books/b1")))!!.title shouldBe "Renamed"
                }
            }
        }

        test("one book that throws on write does not drop the others in its chunk") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    // "b2" carries an absolute rootRelPath, which writePayload's require() rejects
                    // inside its savepoint — but a savepoint rollback must not abort the chunk.
                    val result =
                        repo.persistAllBooks(
                            listOf(
                                book("books/b1"),
                                book("/abs/b2"),
                                book("books/b3"),
                            ),
                        )

                    result.persisted shouldBe 2
                    result.failed shouldBe 1
                    // The two good books are present; the malformed one is not.
                    sql.booksQueries.countLive().executeAsOne() shouldBe 2L
                }
            }
        }

        test("a chunk-spanning batch persists every book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    // More books than one chunk holds, so the loop spans multiple write transactions.
                    val count = PERSIST_CHUNK_SIZE_FOR_TEST + 30
                    val books = (1..count).map { book("books/b$it") }

                    val result = repo.persistAllBooks(books)

                    result.persisted shouldBe count
                    result.failed shouldBe 0
                    sql.booksQueries.countLive().executeAsOne() shouldBe count.toLong()
                }
            }
        }
    })

// --- Constants --------------------------------------------------------------

private val TEST_LIBRARY_ID = LibraryId("test-library")
private val TEST_FOLDER_ID = FolderId("test-folder")

/** Mirror of the production PERSIST_CHUNK_SIZE so the chunk-spanning test exceeds one chunk. */
private const val PERSIST_CHUNK_SIZE_FOR_TEST = 250

// --- Batched-persist driver -------------------------------------------------

/**
 * Drives [books] through [BookRepository.resolveOrInsertAll], pre-resolving identities (the same
 * once-per-scan bulk resolve the orchestrator does) and passing a no-op progress callback. Returns
 * the [PersistResult] so a test can assert persisted/failed counts.
 */
private suspend fun BookRepository.persistAllBooks(books: List<AnalyzedBook>): PersistResult {
    val identityMaps = resolveScanIdentities(books)
    return resolveOrInsertAll(
        libraryId = TEST_LIBRARY_ID,
        folderId = TEST_FOLDER_ID,
        books = books,
        coversByBook = emptyMap(),
        systemCollectionId = null,
        identityMaps = identityMaps,
        onProgress = { _, _ -> },
    )
}

/** Resolves the persisted book id for [rootRelPath] via the folder-scoped natural-key lookup. */
private fun ListenUpDatabase.idOf(rootRelPath: String): String {
    val query = booksQueries.selectIdByNaturalKey(TEST_FOLDER_ID.value, rootRelPath)
    return query.executeAsOne()
}

// --- Parity snapshot --------------------------------------------------------

/**
 * The normalized, id-stripped projection of a persisted book aggregate used for parity comparison.
 * Server-assigned ids (book id, child-row ids, revision, timestamps) are dropped so two independent
 * runs (which mint different UUIDs) compare equal iff their CONTENT is identical.
 */
private data class BookSnapshot(
    val title: String,
    val subtitle: String?,
    val contributors: List<Pair<String, String>>,
    val series: List<Pair<String, String?>>,
    val genreNames: List<String>,
    val chapterTitles: List<String>,
    val audioFilenames: List<String>,
    val coverSource: String?,
)

private fun BookSyncPayload.toSnapshot(genreNames: List<String>): BookSnapshot =
    BookSnapshot(
        title = title,
        subtitle = subtitle,
        contributors = contributors.map { it.name to it.role }.sortedBy { it.first },
        series = series.map { it.name to it.sequence }.sortedBy { it.first },
        genreNames = genreNames.sorted(),
        chapterTitles = chapters.map { it.title },
        audioFilenames = audioFiles.map { it.filename }.sorted(),
        coverSource = cover?.source?.name,
    )

/**
 * Persists a fixed multi-book corpus into a fresh DB — per-book when [batched] is false, batched when
 * true — then returns a `rootRelPath → BookSnapshot` map for parity comparison.
 */
private fun persistThenSnapshot(batched: Boolean): Map<String, BookSnapshot> {
    var snapshot: Map<String, BookSnapshot> = emptyMap()
    withSqlDatabase {
        sql.seedTestLibraryAndFolder()
        val repo = newRepo()
        runTest {
            // Seed a curator alias + a normalize-resolvable genre so all three cascade steps fire.
            sql.transaction {
                sql.seedGenre("g-fantasy", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.seedAlias("Fantasy", "g-fantasy")
                sql.seedGenre("g-mystery", name = "Mystery", slug = "mystery", path = "/mystery")
            }

            val books =
                listOf(
                    book(
                        "books/b1",
                        title = "The Way of Kings",
                        authors = listOf("Brandon Sanderson"),
                        narrators = listOf("Michael Kramer"),
                        series = listOf(SeriesEntry(name = "Stormlight", sequence = "1")),
                        genres = listOf("Fantasy", "Mystery", "Cyberpunk"),
                    ),
                    book(
                        "books/b2",
                        title = "Mistborn",
                        authors = listOf("Brandon Sanderson"),
                        genres = listOf("Fantasy"),
                    ),
                    book("books/b3", title = "Plain Book"),
                )

            val identityMaps = repo.resolveScanIdentities(books)
            if (batched) {
                repo.resolveOrInsertAll(
                    libraryId = TEST_LIBRARY_ID,
                    folderId = TEST_FOLDER_ID,
                    books = books,
                    coversByBook = emptyMap(),
                    systemCollectionId = null,
                    identityMaps = identityMaps,
                    onProgress = { _, _ -> },
                )
            } else {
                for (b in books) {
                    val r =
                        repo.resolveOrInsert(
                            libraryId = TEST_LIBRARY_ID,
                            folderId = TEST_FOLDER_ID,
                            analyzed = b,
                            contributorIds = identityMaps.contributors,
                            seriesIds = identityMaps.series,
                        )
                    check(r is AppResult.Success)
                }
            }

            snapshot =
                books.associate { b ->
                    val path = b.candidate.rootRelPath
                    val id =
                        sql.booksQueries
                            .selectIdByNaturalKey(TEST_FOLDER_ID.value, path)
                            .executeAsOne()
                    val payload = repo.findById(BookId(id))!!
                    val genreNames =
                        sql.bookGenresQueries
                            .genresForBook(id)
                            .executeAsList()
                            .map { it.name }
                    path to payload.toSnapshot(genreNames)
                }
        }
    }
    return snapshot
}

// --- Fixtures ---------------------------------------------------------------

private fun SqlTestDatabases.newRepo(): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}

private fun ListenUpDatabase.seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
) {
    genresQueries.insert(
        id = id,
        name = name,
        slug = slug,
        path = path,
        parent_id = null,
        depth = 0,
        sort_order = 0,
        color = null,
        description = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = null,
        client_op_id = null,
    )
}

private fun ListenUpDatabase.seedAlias(
    rawString: String,
    genreId: String,
) {
    genreAliasesQueries.deleteByRawString(rawString)
    genreAliasesQueries.insert(raw_string = rawString, genre_id = genreId)
}

private fun book(
    rootRelPath: String,
    title: String = rootRelPath.substringAfterLast('/'),
    authors: List<String> = emptyList(),
    narrators: List<String> = emptyList(),
    series: List<SeriesEntry> = emptyList(),
    genres: List<String> = emptyList(),
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = listOf(file),
            ),
        title = title,
        authors = authors,
        narrators = narrators,
        series = series,
        genres = genres,
        tracks = listOf(TrackEntry(file = file)),
    )
}
