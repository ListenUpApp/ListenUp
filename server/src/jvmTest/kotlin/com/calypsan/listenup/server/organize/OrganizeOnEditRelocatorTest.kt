package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.SqlLibraryRootProvider
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.librarywrite.tempJournalDir
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * [OrganizeOnEditRelocator] — the metadata-edit hook, and the one rule that makes always-on
 * organization safe: **conformance is maintained, never imposed**. A book that was already at its
 * canonical path before the edit is kept there afterwards; a book its owner filed somewhere else
 * is left alone, however much the edit changed what the rules would produce for it.
 *
 * Drives the relocator directly (the `BookServiceImpl` call sites are one-line notifications).
 */
class OrganizeOnEditRelocatorTest :
    FunSpec({

        test("a book that WAS canonical before the edit is kept canonical after it") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-relocate-canonical-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedAuthorRow(sql)
                // Already filed by the rules: Author/Title under AUTHOR_TITLE.
                val bookDir =
                    libraryRoot.resolve("Brandon Sanderson/Elantris").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    runBlocking {
                        val repo = makeBookRepository(this@withSqlDatabase)
                        val preEdit =
                            bookPayload(title = "Elantris", rootRelPath = "Brandon Sanderson/Elantris")
                        repo.upsert(preEdit)
                        // The edit itself: the title changes; the path has not caught up yet.
                        repo.upsert(
                            bookPayload(title = "The Way of Kings", rootRelPath = "Brandon Sanderson/Elantris"),
                        )
                        val relocator = makeRelocator(this@withSqlDatabase, repo, scope)

                        relocator.onBookEdited(BookId("b1"), preEdit)

                        eventually(10.seconds) {
                            sql.booksQueries
                                .selectById("b1")
                                .executeAsOne()
                                .root_rel_path shouldBe "Brandon Sanderson/The Way of Kings"
                            // Single-file book: the audio file takes the folder's name too.
                            libraryRoot
                                .resolve("Brandon Sanderson/The Way of Kings/The Way of Kings.m4b")
                                .toFile()
                                .exists() shouldBe true
                        }
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a book that was NOT canonical before the edit is left exactly where its owner put it") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-relocate-untouched-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedAuthorRow(sql)
                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    runBlocking {
                        val repo = makeBookRepository(this@withSqlDatabase)
                        // Filed by hand under `messy/` — never under the organizer's care.
                        val preEdit = bookPayload(title = "Elantris", rootRelPath = "messy")
                        repo.upsert(preEdit)
                        repo.upsert(bookPayload(title = "The Way of Kings", rootRelPath = "messy"))
                        val relocator = makeRelocator(this@withSqlDatabase, repo, scope)

                        relocator.onBookEdited(BookId("b1"), preEdit)
                        delay(1_000) // well past the debounce — nothing should have happened

                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOne()
                            .root_rel_path shouldBe "messy"
                        bookDir.resolve("01.m4b").toFile().exists() shouldBe true
                        libraryRoot.resolve("Brandon Sanderson").toFile().exists() shouldBe false
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    })

/** A relocator over the real broker/executor graph, with the AUTHOR_TITLE schema persisted. */
private suspend fun makeRelocator(
    db: SqlTestDatabases,
    repo: BookRepository,
    scope: CoroutineScope,
): OrganizeOnEditRelocator {
    val store = OrganizerSettingsStore(ServerSettingsRepository(db.sql, default = RegistrationPolicy.CLOSED))
    store.set(OrganizeSettingsDto(preset = OrganizePreset.AUTHOR_TITLE))
    return OrganizeOnEditRelocator(
        settingsStore = store,
        planBuilder = OrganizePlanBuilder(db.sql),
        executor =
            MoveManifestExecutor(
                LibraryWriteBroker(
                    SelfWriteRegistry { 0L },
                    WriteJournal(tempJournalDir()),
                    SqlLibraryRootProvider(db.sql),
                ),
                repo,
            ),
        scope = scope,
        debounceMs = 50,
    )
}

private fun makeBookRepository(db: SqlTestDatabases): BookRepository {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    return BookRepository(
        db = db.sql,
        driver = db.driver,
        bus = bus,
        registry = registry,
        contributorRepository = ContributorRepository(db.sql, bus, registry),
        seriesRepository = SeriesRepository(db.sql, bus, registry),
        genreRepository = GenreRepository(db.sql, bus, registry),
    )
}

private fun seedAuthorRow(sql: ListenUpDatabase) {
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
            birth_date = null,
            death_date = null,
            website = null,
        )
    }
}

/** One single-file book by Brandon Sanderson — the shape both cases seed and edit. */
private fun bookPayload(
    title: String,
    rootRelPath: String,
): BookSyncPayload =
    bookPayloadFixture(
        id = "b1",
        title = title,
        rootRelPath = rootRelPath,
        contributors =
            listOf(
                BookContributorPayload(
                    id = "c1",
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af1",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 1_000L,
                    size = 1_000L,
                ),
            ),
    )
