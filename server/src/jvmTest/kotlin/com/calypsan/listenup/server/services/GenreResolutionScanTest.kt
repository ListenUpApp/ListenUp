@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.seed.GenreDomainSeeder
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * DB-backed coverage for the scanner's 3-step genre-resolution cascade in
 * [BookGenreWriter.processGenreStrings]: curator alias ([genre_aliases]) →
 * built-in normalization ([GenreNormalizer] against the live taxonomy) →
 * auto-create a flat live genre ([GenreAutoCreator]).
 *
 * Seeds the real taxonomy via [GenreDomainSeeder] so the built-in normalizer's
 * canonical slugs resolve against actual genre rows.
 */
class GenreResolutionScanTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        test("built-in normalizer resolves a known raw string; unknown auto-creates a live genre") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    seedTaxonomy(fixedClock)

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(
                            rootRelPath = "books/b1",
                            genres = listOf("Sci-Fi", "Totally Unknown Genre Xyz"),
                        ),
                    )

                    val sciFiId = sql.genresQueries.findBySlug("science-fiction").executeAsOneOrNull()
                    sciFiId shouldNotBe null
                    // Known string normalizes to the seeded genre; unknown string is
                    // auto-created as a flat live genre, so both land in book_genres.
                    val newGenreId = sql.genresQueries.findBySlug("totally-unknown-genre-xyz").executeAsOneOrNull()
                    newGenreId shouldNotBe null
                    val bookGenreIds =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.id }
                    bookGenreIds shouldContainExactlyInAnyOrder listOf(sciFiId, newGenreId)
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Totally Unknown Genre Xyz")
                        .executeAsList()
                        .shouldBeEmpty()
                    // No alias row was created — built-in resolution doesn't touch genre_aliases.
                    sql.genreAliasesQueries
                        .aliasesForGenre(sciFiId!!)
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("curator alias wins over built-in normalization") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    seedTaxonomy(fixedClock)
                    val fantasyId = sql.genresQueries.findBySlug("fantasy").executeAsOneOrNull()!!
                    // "Sci-Fi" would normalize to science-fiction, but the curator alias overrides.
                    sql.transaction {
                        sql.genreAliasesQueries.deleteByRawString("Sci-Fi")
                        sql.genreAliasesQueries.insert(raw_string = "Sci-Fi", genre_id = fantasyId)
                    }

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("Sci-Fi")),
                    )

                    val bookGenreIds =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.id }
                    bookGenreIds shouldContainExactly listOf(fantasyId)
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Sci-Fi")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("every canonical slug the normalizer can emit exists in the seeded taxonomy") {
            withSqlDatabase {
                runTest {
                    seedTaxonomy(fixedClock)
                    GenreNormalizer.canonicalSlugs().forEach { slug ->
                        sql.genresQueries.findBySlug(slug).executeAsOneOrNull() shouldNotBe null
                    }
                }
            }
        }

        test("rescanning the same string is idempotent — one book_genres row, no pending") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    seedTaxonomy(fixedClock)

                    repeat(2) {
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/b1", genres = listOf("Sci-Fi")),
                        )
                    }

                    val sciFiId = sql.genresQueries.findBySlug("science-fiction").executeAsOneOrNull()
                    val bookGenreIds =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.id }
                    bookGenreIds shouldContainExactly listOf(sciFiId)
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Sci-Fi")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private suspend fun SqlTestDatabases.seedTaxonomy(clock: FixedClock) {
    GenreDomainSeeder(
        genreRepository = GenreRepository(sql, ChangeBus(), SyncRegistry(), clock),
        clock = clock,
    ).seed()
}

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

private fun analyzedFixture(
    rootRelPath: String,
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
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
        genres = genres,
    )
}
