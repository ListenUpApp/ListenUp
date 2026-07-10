package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.organize.OrganizePreset
import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeSettingsDto
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.librarywrite.tempJournalDir
import com.calypsan.listenup.server.organize.MoveManifestExecutor
import com.calypsan.listenup.server.organize.OrganizePlanBuilder
import com.calypsan.listenup.server.organize.OrganizeRunState
import com.calypsan.listenup.server.organize.OrganizerSettingsStore
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

private const val RUN_TIMEOUT_MS = 30_000L

/**
 * [OrganizeServiceImpl] — admin gating, settings persistence, preview counts, the save-moment
 * run lifecycle (events to terminal), and the Never Stranded broker-unavailable branch (typed
 * failure, toggle stays off).
 */
class OrganizeServiceImplTest :
    FunSpec({

        fun principalFor(
            userId: String,
            role: UserRole,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        test("member is denied on every method") {
            withSqlDatabase {
                runTest {
                    val svc = makeOrganizeService(this@withSqlDatabase, principalFor("m1", UserRole.MEMBER))
                    listOf(
                        svc.getSettings() as AppResult<*>,
                        svc.preview(OrganizeSettingsDto()) as AppResult<*>,
                        svc.saveAndExecute(OrganizeSettingsDto(enabled = true)) as AppResult<*>,
                        svc.resumeRun() as AppResult<*>,
                    ).forEach { result ->
                        val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                        failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                    }
                }
            }
        }

        test("getSettings returns defaults when never configured") {
            withSqlDatabase {
                runTest {
                    val svc = makeOrganizeService(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))
                    val settings = (svc.getSettings() as AppResult.Success).data
                    settings shouldBe OrganizeSettingsDto()
                    settings.enabled.shouldBeFalse()
                }
            }
        }

        test("preview against seeded books returns correct counts without touching disk or settings") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-organize-svc-preview-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedAuthor(sql)
                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                runTest {
                    seedBook(this@withSqlDatabase, id = "b1", rootRelPath = "messy")
                    val svc = makeOrganizeService(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))

                    val preview =
                        (
                            svc.preview(
                                OrganizeSettingsDto(preset = OrganizePreset.AUTHOR_TITLE),
                            ) as AppResult.Success
                        ).data

                    preview.bookCount shouldBe 1
                    preview.fileCount shouldBe 1
                    preview.collisionCount shouldBe 0
                    preview.entries.shouldNotBeEmpty()
                    preview.entries.single().toPath shouldBe "Brandon Sanderson/The Way of Kings"
                    // Preview persists nothing.
                    (svc.getSettings() as AppResult.Success).data shouldBe OrganizeSettingsDto()
                    // And moves nothing.
                    bookDir.resolve("01.m4b").toFile().exists() shouldBe true
                }
            }
        }

        test("saveAndExecute persists settings, runs to terminal Completed, and moves the book") {
            withSqlDatabase {
                val libraryRoot = Files.createTempDirectory("listenup-organize-svc-run-")
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                seedAuthor(sql)
                val bookDir = libraryRoot.resolve("messy").also { Files.createDirectories(it) }
                Files.writeString(bookDir.resolve("01.m4b"), "a")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    runBlocking {
                        seedBook(this@withSqlDatabase, id = "b1", rootRelPath = "messy")
                        val svc =
                            makeOrganizeService(
                                this@withSqlDatabase,
                                principalFor("a1", UserRole.ADMIN),
                                runScope = scope,
                            )
                        val wanted = OrganizeSettingsDto(enabled = true, preset = OrganizePreset.AUTHOR_TITLE)

                        val runId = (svc.saveAndExecute(wanted) as AppResult.Success).data

                        val events =
                            withTimeout(RUN_TIMEOUT_MS) {
                                svc.observeRun(runId).toList().map { (it as RpcEvent.Data).value }
                            }
                        withClue("events: $events") {
                            events.first().shouldBeInstanceOf<OrganizeRunEvent.Started>()
                            val terminal = events.last().shouldBeInstanceOf<OrganizeRunEvent.Completed>()
                            terminal.movedBooks shouldBe 1
                            terminal.failedBooks shouldBe 0
                        }

                        (svc.getSettings() as AppResult.Success).data shouldBe wanted
                        libraryRoot
                            .resolve("Brandon Sanderson/The Way of Kings/01.m4b")
                            .toFile()
                            .exists() shouldBe true
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOne()
                            .root_rel_path shouldBe
                            "Brandon Sanderson/The Way of Kings"
                        // Run finished — nothing to resume.
                        (svc.resumeRun() as AppResult.Success).data.shouldBeNull()
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        test("unwritable library root: saveAndExecute fails typed and the toggle stays off") {
            withSqlDatabase {
                // Root path that doesn't exist — the broker probe reports Unavailable.
                sql.seedTestLibraryAndFolder(folderPath = "/nonexistent/listenup-organize-root")
                runTest {
                    val svc = makeOrganizeService(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))

                    val result = svc.saveAndExecute(OrganizeSettingsDto(enabled = true))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<LibraryWriteError.Unavailable>()
                    (svc.getSettings() as AppResult.Success).data.enabled.shouldBeFalse()
                }
            }
        }

        test("saving with enabled=false persists the schema and starts no run") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(folderPath = "/nonexistent/listenup-organize-root")
                runTest {
                    val svc = makeOrganizeService(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))
                    val wanted = OrganizeSettingsDto(enabled = false, preset = OrganizePreset.FLAT_TITLE)

                    // Even against an unwritable root — disabling must always be possible (Never Stranded).
                    val result = svc.saveAndExecute(wanted)

                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                    (svc.getSettings() as AppResult.Success).data shouldBe wanted
                    (svc.resumeRun() as AppResult.Success).data.shouldBeNull()
                }
            }
        }
    })

/** Builds an [OrganizeServiceImpl] over the test db with a real broker/executor graph. */
private fun SqlTestDatabases.makeOrganizeService(
    db: SqlTestDatabases,
    principal: PrincipalProvider,
    runScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): OrganizeServiceImpl {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val bookRepository =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(db.sql, bus, registry),
            seriesRepository = SeriesRepository(db.sql, bus, registry),
            genreRepository = GenreRepository(db.sql, bus, registry),
        )
    val broker = LibraryWriteBroker(SelfWriteRegistry { 0L }, WriteJournal(tempJournalDir()))
    return OrganizeServiceImpl(
        settingsStore = OrganizerSettingsStore(ServerSettingsRepository(db.sql, default = defaultRegistrationPolicy())),
        planBuilder = OrganizePlanBuilder(db.sql),
        executor = MoveManifestExecutor(broker, bookRepository),
        broker = broker,
        libraryRegistry = LibraryRegistry(db.sql),
        sql = db.sql,
        runState = OrganizeRunState(),
        runScope = runScope,
        principal = principal,
    )
}

private fun defaultRegistrationPolicy() = com.calypsan.listenup.api.dto.auth.RegistrationPolicy.CLOSED

private fun seedAuthor(sql: ListenUpDatabase) {
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

private suspend fun seedBook(
    db: SqlTestDatabases,
    id: String,
    rootRelPath: String,
) {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val repo =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(db.sql, bus, registry),
            seriesRepository = SeriesRepository(db.sql, bus, registry),
            genreRepository = GenreRepository(db.sql, bus, registry),
        )
    repo.upsert(
        bookPayloadFixture(
            id = id,
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
