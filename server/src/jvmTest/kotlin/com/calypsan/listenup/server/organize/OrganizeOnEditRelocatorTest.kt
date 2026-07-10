package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
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
 * [OrganizeOnEditRelocator] — the metadata-edit hook: a title/author/series edit that changes a
 * book's canonical path relocates the book (debounced), and a disabled organizer relocates
 * nothing. Drives the relocator directly (the BookServiceImpl call sites are one-line
 * notifications).
 */
class OrganizeOnEditRelocatorTest :
    FunSpec({

        test("enabled organizer relocates an edited book whose canonical path changed") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-relocate-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedAuthorRow(sql)
                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    runBlocking {
                        val repo = makeBookRepository(this@withSqlDatabase)
                        seedOneBook(repo, rootRelPath = "messy")
                        val settingsRepo = ServerSettingsRepository(sql, default = RegistrationPolicy.CLOSED)
                        val store = OrganizerSettingsStore(settingsRepo)
                        store.set(OrganizeSettingsDto(enabled = true, preset = OrganizePreset.AUTHOR_TITLE))
                        val relocator =
                            OrganizeOnEditRelocator(
                                settingsStore = store,
                                planBuilder = OrganizePlanBuilder(sql),
                                executor =
                                    MoveManifestExecutor(
                                        LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir())),
                                        repo,
                                    ),
                                scope = scope,
                                debounceMs = 50,
                            )

                        relocator.onBookEdited(BookId("b1"))

                        eventually(10.seconds) {
                            sql.booksQueries
                                .selectById("b1")
                                .executeAsOne()
                                .root_rel_path shouldBe
                                "Brandon Sanderson/The Way of Kings"
                            libraryRoot
                                .resolve("Brandon Sanderson/The Way of Kings/01.m4b")
                                .toFile()
                                .exists() shouldBe true
                        }
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("disabled organizer relocates nothing") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-relocate-off-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedAuthorRow(sql)
                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    runBlocking {
                        val repo = makeBookRepository(this@withSqlDatabase)
                        seedOneBook(repo, rootRelPath = "messy")
                        val settingsRepo = ServerSettingsRepository(sql, default = RegistrationPolicy.CLOSED)
                        val store = OrganizerSettingsStore(settingsRepo)
                        store.set(OrganizeSettingsDto(enabled = false, preset = OrganizePreset.AUTHOR_TITLE))
                        val relocator =
                            OrganizeOnEditRelocator(
                                settingsStore = store,
                                planBuilder = OrganizePlanBuilder(sql),
                                executor =
                                    MoveManifestExecutor(
                                        LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir())),
                                        repo,
                                    ),
                                scope = scope,
                                debounceMs = 50,
                            )

                        relocator.onBookEdited(BookId("b1"))
                        delay(1_000) // well past the debounce — nothing should have happened

                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOne()
                            .root_rel_path shouldBe "messy"
                        bookDir.resolve("01.m4b").toFile().exists() shouldBe true
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    })

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
            image_blur_hash = null,
            birth_date = null,
            death_date = null,
            website = null,
        )
    }
}

private suspend fun seedOneBook(
    repo: BookRepository,
    rootRelPath: String,
) {
    repo.upsert(
        bookPayloadFixture(
            id = "b1",
            title = "The Way of Kings",
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
        ),
    )
}
