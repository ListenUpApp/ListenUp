package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

/**
 * [OrganizePlanBuilder] against a real (small) SQLite-backed library: seeded books produce a
 * [MovePlan] whose entries reflect [OrganizerPathPlanner]'s canonical output, collisions get a
 * deterministic suffix, already-canonical books are excluded, and every on-disk file under a
 * moving book's folder — not just the ones tracked in `book_audio_files` — is captured.
 */
class OrganizePlanBuilderTest :
    FunSpec({
        val settings =
            OrganizerSettings(
                preset = StructurePreset.AUTHOR_SERIES_TITLE,
                seriesPrefix = SeriesPrefixStyle.BOOK_N_DASH,
                authorForm = AuthorForm.FIRST_LAST,
            )

        test("plans a move for a book whose folder does not match the canonical path") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(folderPath = tempLibraryRoot().toString())
                val libraryRoot =
                    java.nio.file.Paths
                        .get(currentFolderRoot(sql))
                seedCatalog(sql)

                val bookDir = libraryRoot.resolve("messy-folder").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "audio")
                Files.writeString(bookDir.resolve("cover.jpg"), "cover")

                runTest {
                    val repo = buildBookRepository(sql, driver)
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            rootRelPath = "messy-folder",
                            contributors = listOf(author("c1", "Brandon Sanderson")),
                            series = listOf(seriesMembership("s1", "Stormlight Archive", "1")),
                            audioFiles = listOf(audioFile("af1", "01.m4b")),
                        ),
                    )

                    val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)

                    plan.bookCount shouldBe 1
                    val entry = plan.entries.single()
                    entry.bookId shouldBe "b1"
                    entry.toRootRelPath shouldBe "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
                    entry.collisionResolved shouldBe false
                    // Both the tracked audio file AND the untracked cover.jpg sidecar travel.
                    entry.files.map { it.from.name }.toSet() shouldBe setOf("01.m4b", "cover.jpg")
                    entry.files.forEach { move ->
                        move.to.toString() shouldBe
                            libraryRoot
                                .resolve("Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings")
                                .resolve(move.from.name)
                                .toString()
                    }
                }
            }
        }

        test("a book already at its canonical path is excluded from the plan") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(folderPath = tempLibraryRoot().toString())
                seedCatalog(sql)

                runTest {
                    val repo = buildBookRepository(sql, driver)
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            rootRelPath = "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings",
                            contributors = listOf(author("c1", "Brandon Sanderson")),
                            series = listOf(seriesMembership("s1", "Stormlight Archive", "1")),
                            audioFiles = listOf(audioFile("af1", "01.m4b")),
                        ),
                    )

                    val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)

                    plan.bookCount shouldBe 0
                    plan.entries shouldBe emptyList()
                }
            }
        }

        test("two books resolving to the same target dir: the second (by bookId) gets a ' (2)' suffix") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(folderPath = tempLibraryRoot().toString())
                seedCatalog(sql)

                runTest {
                    val repo = buildBookRepository(sql, driver)
                    // Two distinct books with identical author/series/title metadata — a legitimate
                    // (if unusual) collision, e.g. two different editions catalogued the same way.
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            rootRelPath = "old-1",
                            contributors = listOf(author("c1", "Brandon Sanderson")),
                            series = listOf(seriesMembership("s1", "Stormlight Archive", "1")),
                            audioFiles = listOf(audioFile("af1", "01.m4b")),
                        ),
                    )
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b2",
                            title = "The Way of Kings",
                            rootRelPath = "old-2",
                            contributors = listOf(author("c1", "Brandon Sanderson")),
                            series = listOf(seriesMembership("s1", "Stormlight Archive", "1")),
                            audioFiles = listOf(audioFile("af1", "01.m4b")),
                        ),
                    )

                    val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)

                    plan.bookCount shouldBe 2
                    plan.collisionCount shouldBe 1
                    val byId = plan.entries.associateBy { it.bookId }
                    byId.getValue("b1").toRootRelPath shouldBe
                        "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings"
                    byId.getValue("b1").collisionResolved shouldBe false
                    byId.getValue("b2").toRootRelPath shouldBe
                        "Brandon Sanderson/Stormlight Archive/Book 1 - The Way of Kings (2)"
                    byId.getValue("b2").collisionResolved shouldBe true
                }
            }
        }

        test("plan summary counts total files across every moving book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(folderPath = tempLibraryRoot().toString())
                val libraryRoot =
                    java.nio.file.Paths
                        .get(currentFolderRoot(sql))
                seedCatalog(sql)

                val dir1 = libraryRoot.resolve("m1").also { Files.createDirectories(it) }
                Files.writeString(dir1.resolve("01.m4b"), "a")
                Files.writeString(dir1.resolve("02.m4b"), "a")
                val dir2 = libraryRoot.resolve("m2").also { Files.createDirectories(it) }
                Files.writeString(dir2.resolve("01.m4b"), "a")

                runTest {
                    val repo = buildBookRepository(sql, driver)
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "First Book",
                            rootRelPath = "m1",
                            contributors = listOf(author("c1", "Brandon Sanderson")),
                            audioFiles = listOf(audioFile("af1", "01.m4b"), audioFile("af2", "02.m4b", index = 1)),
                        ),
                    )
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b2",
                            title = "Second Book",
                            rootRelPath = "m2",
                            contributors = listOf(author("c1", "Brandon Sanderson")),
                            audioFiles = listOf(audioFile("af1", "01.m4b")),
                        ),
                    )

                    val plan = OrganizePlanBuilder(sql).build(LibraryId("test-library"), settings)

                    plan.bookCount shouldBe 2
                    plan.fileCount shouldBe 3
                    plan.collisionCount shouldBe 0
                }
            }
        }
    })

private fun tempLibraryRoot(): java.nio.file.Path = Files.createTempDirectory("listenup-organize-plan-")

private fun currentFolderRoot(sql: ListenUpDatabase): String =
    sql.libraryFoldersQueries
        .selectById("test-folder")
        .executeAsOne()
        .root_path

/** Seeds the `contributors`/`book_series` catalogue rows the junction-row FKs require. */
private fun seedCatalog(sql: ListenUpDatabase) {
    sql.transaction {
        sql.contributorsQueries.insert(
            id = "c1",
            normalized_name = "brandon sanderson",
            name = "Brandon Sanderson",
            sort_name = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
            asin = null,
            description = null,
            image_path = null,
            image_blur_hash = null,
            birth_date = null,
            death_date = null,
            website = null,
        )
        sql.seriesQueries.insert(
            id = "s1",
            normalized_name = "stormlight archive",
            name = "Stormlight Archive",
            sort_name = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
            asin = null,
            description = null,
            cover_path = null,
            cover_blur_hash = null,
        )
    }
}

private fun buildBookRepository(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): BookRepository {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = registry,
        contributorRepository = ContributorRepository(sql, bus, registry),
        seriesRepository = SeriesRepository(sql, bus, registry),
        genreRepository = GenreRepository(sql, bus, registry),
    )
}

private fun author(
    id: String,
    name: String,
): BookContributorPayload = BookContributorPayload(id = id, name = name, sortName = null, role = "author", creditedAs = null)

private fun seriesMembership(
    id: String,
    name: String,
    sequence: String?,
): BookSeriesPayload = BookSeriesPayload(id = id, name = name, sequence = sequence)

private fun audioFile(
    id: String,
    filename: String,
    index: Int = 0,
): BookAudioFilePayload =
    BookAudioFilePayload(
        id = id,
        index = index,
        filename = filename,
        format = "m4b",
        codec = "aac",
        duration = 1_000L,
        size = 1_000L,
    )
