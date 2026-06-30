@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Per-field user-edit provenance: a hand-edit to title/subtitle/description/contributors/series must
 * survive a later rescan, exactly as `chapter_source = 'user'` protects an edited chapter set. Each
 * test drives the REAL scan path ([BookRepository.resolveOrInsert] → `upsertFromAnalyzed`), where the
 * merge lives — a raw `repo.upsert` bypasses it (the scanner is the only producer of empty-provenance
 * payloads, so that is the only path that can clobber).
 */
class UserEditedFieldsOnRescanTest :
    FunSpec({

        test("USER title survives a rescan that re-derives a different title from the files") {
            withSqlDatabase {
                val (repo, registry) = userEditRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Mistborn"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    // User edits the title in the app: write the user value + TITLE provenance.
                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(title = "The Final Empire", userEditedFields = setOf(UserEditedField.TITLE)),
                    )

                    // Rescan re-derives a different title from the files.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, title = "Mistborn 01"))

                    repo.findById(id)!!.title shouldBe "The Final Empire"
                }
            }
        }

        test("USER description survives a rescan that re-derives a different description") {
            withSqlDatabase {
                val (repo, registry) = userEditRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/WayOfKings"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(
                            description = "A hand-written blurb.",
                            userEditedFields = setOf(UserEditedField.DESCRIPTION),
                        ),
                    )

                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, description = "Sidecar blurb."))

                    repo.findById(id)!!.description shouldBe "A hand-written blurb."
                }
            }
        }

        test("USER subtitle survives a rescan that re-derives a different subtitle") {
            withSqlDatabase {
                val (repo, registry) = userEditRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Elantris"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(subtitle = "User Subtitle", userEditedFields = setOf(UserEditedField.SUBTITLE)),
                    )

                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, subtitle = "File Subtitle"))

                    repo.findById(id)!!.subtitle shouldBe "User Subtitle"
                }
            }
        }

        test("USER contributors survive a rescan that re-derives different contributors") {
            withSqlDatabase {
                val (repo, registry, contributorRepo) = userEditRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Warbreaker"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    // User curates the contributor list in the app.
                    val curatedId = contributorRepo.resolveOrCreate("Brandon Sanderson", null)
                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(
                            contributors =
                                listOf(
                                    BookContributorPayload(
                                        id = curatedId.value,
                                        name = "Brandon Sanderson",
                                        sortName = null,
                                        role = "author",
                                        creditedAs = null,
                                    ),
                                ),
                            userEditedFields = setOf(UserEditedField.CONTRIBUTORS),
                        ),
                    )

                    // Rescan finds a sloppy embedded author tag.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, authors = listOf("B. Sanderson (Author)")))

                    repo.findById(id)!!.contributors.map { it.name } shouldBe listOf("Brandon Sanderson")
                }
            }
        }

        test("USER series survive a rescan that re-derives different series") {
            withSqlDatabase {
                val fixture = userEditRepository(sql, driver)
                val repo = fixture.repo
                val registry = fixture.registry
                val seriesRepo = fixture.series
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Stormlight01"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    val curatedId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(
                            series =
                                listOf(
                                    BookSeriesPayload(id = curatedId.value, name = "The Stormlight Archive", sequence = "1"),
                                ),
                            userEditedFields = setOf(UserEditedField.SERIES),
                        ),
                    )

                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, series = listOf(SeriesEntry("Stormlight", "1.0"))))

                    repo.findById(id)!!.series.map { it.name } shouldBe listOf("The Stormlight Archive")
                }
            }
        }

        test("a rescan that only disagrees on a protected field skips — no revision bump") {
            withSqlDatabase {
                val (repo, registry) = userEditRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Skip"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    // Use description: unlike title (which cascades to the derived sortTitle), a
                    // description edit changes no other stored field, so the merged rescan matches
                    // stored in every column and the idempotency check skips it cleanly.
                    repo.upsert(
                        repo.findById(id)!!.copy(
                            description = "Curated blurb.",
                            userEditedFields = setOf(UserEditedField.DESCRIPTION),
                        ),
                    )
                    val revisionAfterEdit = repo.findById(id)!!.revision

                    // Rescan whose ONLY difference from stored is the (protected) description.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, description = "Re-derived blurb."))

                    repo.findById(id)!!.revision shouldBe revisionAfterEdit
                }
            }
        }

        test("a non-protected field is still updated by a rescan, even alongside a protected one") {
            withSqlDatabase {
                val (repo, registry) = userEditRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Publisher"
                    val id =
                        repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, publisher = "Tor V1")).resolved()

                    repo.upsert(
                        repo.findById(id)!!.copy(title = "Curated", userEditedFields = setOf(UserEditedField.TITLE)),
                    )

                    // Rescan changes BOTH the protected title and the unprotected publisher.
                    repo.resolveOrInsert(
                        libId,
                        TEST_FOLDER,
                        scanFor(path, title = "Re-derived", publisher = "Tor V2"),
                    )

                    val readback = repo.findById(id)!!
                    readback.title shouldBe "Curated" // protected — preserved
                    readback.publisher shouldBe "Tor V2" // not protected — updated
                }
            }
        }

        test("userEditedFields round-trips through the row (serialize → column → deserialize)") {
            withSqlDatabase {
                val (repo, registry) = userEditRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val id =
                        repo.resolveOrInsert(libId, TEST_FOLDER, scanFor("Sanderson/RoundTrip")).resolved()

                    val edited =
                        setOf(UserEditedField.DESCRIPTION, UserEditedField.TITLE, UserEditedField.SERIES)
                    repo.upsert(repo.findById(id)!!.copy(userEditedFields = edited))

                    repo.findById(id)!!.userEditedFields shouldBe edited
                }
            }
        }
    })

private val TEST_FOLDER = FolderId("test-folder")

private fun AppResult<IngestOutcome>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

/** A [BookRepository] plus the catalogues a user-edit test plants curated contributor/series rows through. */
private data class UserEditFixture(
    val repo: BookRepository,
    val registry: LibraryRegistry,
    val contributors: ContributorRepository,
    val series: SeriesRepository,
)

private fun userEditRepository(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): UserEditFixture {
    val registry = LibraryRegistry(sql)
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
    val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
    val repo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(sql, bus, syncRegistry),
        )
    return UserEditFixture(repo, registry, contributorRepo, seriesRepo)
}

/**
 * A minimal [AnalyzedBook] anchored at [rootRelPath] (the natural key a rescan resolves by), with the
 * file-derived metadata a scanner would produce. Defaults model an unedited rescan; override a field
 * to simulate the files disagreeing with a user's hand-edit.
 */
private fun scanFor(
    rootRelPath: String,
    title: String = rootRelPath.substringAfterLast('/'),
    subtitle: String? = null,
    description: String? = null,
    publisher: String? = null,
    authors: List<String> = emptyList(),
    series: List<SeriesEntry> = emptyList(),
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
        title = title,
        subtitle = subtitle,
        description = description,
        publisher = publisher,
        authors = authors,
        series = series,
        tracks = listOf(TrackEntry(file = file)),
    )
}
