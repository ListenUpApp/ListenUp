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
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
import com.calypsan.listenup.server.seed.GenreDomainSeeder
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldNotBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.calypsan.listenup.server.testing.asSqlDatabase

/**
 * DB-backed coverage for the scanner's 3-step genre-resolution cascade in
 * [BookGenreWriter.processGenreStrings]: curator alias ([GenreAliasTable]) →
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val repo = newRepo(db)
                runTest {
                    seedTaxonomy(db, fixedClock)

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(
                            rootRelPath = "books/b1",
                            genres = listOf("Sci-Fi", "Totally Unknown Genre Xyz"),
                        ),
                    )

                    transaction(db) {
                        val sciFiId = GenreTable.findBySlug("science-fiction")
                        sciFiId shouldNotBe null
                        // Known string normalizes to the seeded genre; unknown string is
                        // auto-created as a flat live genre, so both land in book_genres.
                        val newGenreId = GenreTable.findBySlug("totally-unknown-genre-xyz")
                        newGenreId shouldNotBe null
                        BookGenreTable.genresForBook("b1") shouldContainExactlyInAnyOrder
                            listOf(sciFiId, newGenreId)
                        PendingBookGenreTable.bookIdsByRawString("Totally Unknown Genre Xyz").shouldBeEmpty()
                        // No alias row was created — built-in resolution doesn't touch genre_aliases.
                        GenreAliasTable.aliasesForGenre(sciFiId!!).shouldBeEmpty()
                    }
                }
            }
        }

        test("curator alias wins over built-in normalization") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val repo = newRepo(db)
                runTest {
                    seedTaxonomy(db, fixedClock)
                    val fantasyId =
                        transaction(db) {
                            val id = GenreTable.findBySlug("fantasy")!!
                            // "Sci-Fi" would normalize to science-fiction, but the curator alias overrides.
                            GenreAliasTable.addAlias("Sci-Fi", id)
                            id
                        }

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("Sci-Fi")),
                    )

                    transaction(db) {
                        BookGenreTable.genresForBook("b1") shouldContainExactly listOf(fantasyId)
                        PendingBookGenreTable.bookIdsByRawString("Sci-Fi").shouldBeEmpty()
                    }
                }
            }
        }

        test("every canonical slug the normalizer can emit exists in the seeded taxonomy") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    seedTaxonomy(db, fixedClock)
                    transaction(db) {
                        GenreNormalizer.canonicalSlugs().forEach { slug ->
                            GenreTable.findBySlug(slug) shouldNotBe null
                        }
                    }
                }
            }
        }

        test("rescanning the same string is idempotent — one book_genres row, no pending") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val repo = newRepo(db)
                runTest {
                    seedTaxonomy(db, fixedClock)

                    repeat(2) {
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/b1", genres = listOf("Sci-Fi")),
                        )
                    }

                    transaction(db) {
                        val sciFiId = GenreTable.findBySlug("science-fiction")
                        BookGenreTable.genresForBook("b1") shouldContainExactly listOf(sciFiId)
                        PendingBookGenreTable.bookIdsByRawString("Sci-Fi").shouldBeEmpty()
                    }
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private suspend fun seedTaxonomy(
    db: Database,
    clock: FixedClock,
) {
    GenreDomainSeeder(
        genreRepository = GenreRepository(db.asSqlDatabase(), ChangeBus(), SyncRegistry(), clock),
        clock = clock,
    ).seed()
}

private fun newRepo(db: Database): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = db.asSqlDatabase(),
        exposedDb = db,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
        genreRepository = GenreRepository(db.asSqlDatabase(), bus, syncRegistry),
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
