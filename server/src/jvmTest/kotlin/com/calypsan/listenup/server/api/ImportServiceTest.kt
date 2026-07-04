package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.absimport.AbsBackupReader
import com.calypsan.listenup.server.absimport.BookMatcher
import com.calypsan.listenup.server.absimport.ImportAnalyzer
import com.calypsan.listenup.server.absimport.ImportApplier
import com.calypsan.listenup.server.absimport.ImportPaths
import com.calypsan.listenup.server.absimport.ImportStore
import com.calypsan.listenup.server.absimport.MappingValidator
import com.calypsan.listenup.server.absimport.SessionConverter
import com.calypsan.listenup.server.absimport.UserMatcher
import com.calypsan.listenup.server.absimport.buildSyntheticAbsDb
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath

/**
 * Service-level tests for the admin-gated ABS-import surface. They drive the full lifecycle through
 * [ImportServiceImpl] itself (constructed with a ROOT [PrincipalProvider]), exercising analyze →
 * confirmMapping → apply → status → delete, plus the admin gate and validator wiring.
 *
 * The service is constructed directly rather than reached through an authed RPC proxy: the staging,
 * matching, and apply collaborators are all in-process, and a direct construction lets each test bind
 * the exact principal (ROOT vs MEMBER) it needs to assert gating.
 */
class ImportServiceTest :
    FunSpec({

        test("full flow: analyze → confirmMapping → apply → status APPLIED → delete") {
            withSqlDatabase {
                runTest {
                    val (service, importId, store) = stageService(this@withSqlDatabase, principal = rootPrincipal())

                    val analysis = service.analyze(importId)
                    analysis.shouldBeInstanceOf<AppResult.Success<*>>()

                    val confirm =
                        service.confirmMapping(
                            importId,
                            userMappings = mapOf(AbsUserId(ABS_USER) to UserId(LU_USER)),
                            bookOverrides = emptyMap(),
                        )
                    confirm.shouldBeInstanceOf<AppResult.Success<*>>()

                    val applied = service.apply(importId)
                    applied.shouldBeInstanceOf<AppResult.Success<*>>()
                    val appliedData = (applied as AppResult.Success).data
                    appliedData.importedCount shouldBeGreaterThan 0
                    // kings + mist + fidelity resolve to ListenUp books; unresolved + podcast skipped.
                    appliedData.sessionsImported shouldBe 3

                    val summary = service.getImport(importId)
                    summary.shouldBeInstanceOf<AppResult.Success<*>>()
                    (summary as AppResult.Success).data.status shouldBe ImportStatus.APPLIED

                    val list = service.listImports()
                    list.shouldBeInstanceOf<AppResult.Success<*>>()
                    (list as AppResult.Success).data.map { it.id } shouldBe listOf(importId)

                    service.deleteImport(importId).shouldBeInstanceOf<AppResult.Success<*>>()
                    service
                        .getImport(importId)
                        .let { (it as AppResult.Failure).error }
                        .shouldBeInstanceOf<ImportError.ImportNotFound>()

                    // Sanity: the store agrees the directory is gone.
                    store.getImport(importId) shouldBe null
                }
            }
        }

        test("confirmMapping with two ABS users mapped to one ListenUp user fails MappingInvalid") {
            withSqlDatabase {
                runTest {
                    val (service, importId, _) = stageService(this@withSqlDatabase, principal = rootPrincipal())
                    service.analyze(importId)

                    val confirm =
                        service.confirmMapping(
                            importId,
                            userMappings =
                                mapOf(
                                    AbsUserId(ABS_USER) to UserId(LU_USER),
                                    AbsUserId("user-other") to UserId(LU_USER),
                                ),
                            bookOverrides = emptyMap(),
                        )
                    (confirm as AppResult.Failure).error.shouldBeInstanceOf<ImportError.MappingInvalid>()
                }
            }
        }

        test("confirmMapping on an unknown import returns ImportNotFound") {
            withSqlDatabase {
                runTest {
                    val (service, _, _) = stageService(this@withSqlDatabase, principal = rootPrincipal())
                    val confirm =
                        service.confirmMapping(
                            ImportId("does-not-exist"),
                            userMappings = mapOf(AbsUserId(ABS_USER) to UserId(LU_USER)),
                            bookOverrides = emptyMap(),
                        )
                    (confirm as AppResult.Failure).error.shouldBeInstanceOf<ImportError.ImportNotFound>()
                }
            }
        }

        test("apply on an unknown import returns ImportNotFound") {
            withSqlDatabase {
                runTest {
                    val (service, _, _) = stageService(this@withSqlDatabase, principal = rootPrincipal())
                    val applied = service.apply(ImportId("does-not-exist"))
                    (applied as AppResult.Failure).error.shouldBeInstanceOf<ImportError.ImportNotFound>()
                }
            }
        }

        test("every method is denied for a non-admin caller") {
            withSqlDatabase {
                runTest {
                    val (service, importId, _) = stageService(this@withSqlDatabase, principal = memberPrincipal())

                    service.analyze(importId).denied()
                    service.confirmMapping(importId, emptyMap(), emptyMap()).denied()
                    service.apply(importId).denied()
                    service.listImports().denied()
                    service.getImport(importId).denied()
                    service.deleteImport(importId).denied()
                }
            }
        }

        test("observeProgress emits nothing for a non-admin caller") {
            withSqlDatabase {
                runTest {
                    val (service, importId, _) = stageService(this@withSqlDatabase, principal = memberPrincipal())
                    service.observeProgress(importId).toList() shouldHaveSize 0
                }
            }
        }
    })

private const val ABS_USER = "user-simon"
private const val LU_USER = "lu-simon"
private const val LU_KINGS = "lu-kings"
private const val LU_MIST = "lu-mist"

private fun rootPrincipal(): PrincipalProvider = principalWithRole(UserRole.ROOT, "s-root")

private fun memberPrincipal(): PrincipalProvider = principalWithRole(UserRole.MEMBER, "s-member")

private fun principalWithRole(
    role: UserRole,
    sessionId: String,
): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(LU_USER), SessionId(sessionId), role) }

private fun AppResult<*>.denied() {
    (this as AppResult.Failure).error.shouldBeInstanceOf<AuthError.PermissionDenied>()
}

private data class StagedService(
    val service: ImportServiceImpl,
    val importId: ImportId,
    val store: ImportStore,
)

/**
 * Stages the synthetic ABS backup, seeds the matching ListenUp library + user + books, and builds a
 * fully-wired [ImportServiceImpl] bound to [principal]. Analyze is left for the test to call so
 * gating can be asserted on a fresh (unanalyzed) import too.
 */
private suspend fun stageService(
    dbs: SqlTestDatabases,
    principal: PrincipalProvider,
): StagedService {
    val home = Files.createTempDirectory("abs-service-")
    val paths = ImportPaths(IoPath(home.toString())).apply { ensureDirs() }
    val importId = ImportId("abs-service-test")
    Files.createDirectories(
        java.nio.file.Path
            .of(paths.dirFor(importId.value).toString()),
    )
    buildSyntheticAbsDb(
        java.nio.file.Path
            .of(paths.absDbFor(importId.value).toString()),
    )

    val libId = seedLibraryUser(dbs)
    dbs.sql.seedBooks(libId.value)

    val store = ImportStore(paths)
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val repo = PlaybackPositionRepository(db = dbs.sql, bus = bus, registry = registry)
    val statsRepo = UserStatsRepository(db = dbs.sql, bus = bus, registry = registry)
    val listeningEventRepo = ListeningEventRepository(db = dbs.sql, bus = bus, registry = registry)
    val statsBackfill = UserStatsBackfillService(sql = dbs.sql, userStatsRepo = statsRepo)
    val statsRecorder =
        StatsRecorder(
            sql = dbs.sql,
            userStatsRepo = statsRepo,
            bookReadsRepository = BookReadsRepository(db = dbs.sql),
            publicProfileMaintainer = dbs.sql.noOpPublicProfileMaintainer(),
            activityRecorder = dbs.activityRecorder(bus = bus),
            statsBackfill = statsBackfill,
        )
    val analyzer =
        ImportAnalyzer(
            reader = AbsBackupReader(),
            store = store,
            paths = paths,
            bookMatcher = BookMatcher(dbs.sql),
            userMatcher = UserMatcher(),
            libraryRegistry = LibraryRegistry(dbs.sql),
            sql = dbs.sql,
        )
    val applier =
        ImportApplier(
            reader = AbsBackupReader(),
            store = store,
            paths = paths,
            playbackPositionRepository = repo,
            sessionConverter = SessionConverter(),
            listeningEventRepository = listeningEventRepo,
            statsRecorder = statsRecorder,
            changeBus = bus,
        )
    val service =
        ImportServiceImpl(
            store = store,
            analyzer = analyzer,
            applier = applier,
            validator = MappingValidator(dbs.sql),
            eventBus = kotlinx.coroutines.flow.MutableSharedFlow(replay = 0, extraBufferCapacity = 64),
            principal = principal,
        )
    return StagedService(service, importId, store)
}

private suspend fun seedLibraryUser(dbs: SqlTestDatabases): LibraryId {
    val libId = LibraryRegistry(dbs.sql).currentLibrary()
    dbs.sql.seedTestUser(LU_USER)
    return libId
}

/** Seeds two ListenUp books matching the synthetic ABS items (kings by ASIN, mist by title). */
private fun ListenUpDatabase.seedBooks(libraryId: String) {
    val now = 1_730_000_000_000L
    booksQueries.insert(
        id = LU_KINGS,
        library_id = libraryId,
        folder_id = "PENDING-LIB-C",
        title = "The Way of Kings",
        sort_title = null,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = "B00ASIN001",
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 1L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        user_edited_fields = "",
        root_rel_path = "Sanderson/Way of Kings",
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
    booksQueries.insert(
        id = LU_MIST,
        library_id = libraryId,
        folder_id = "PENDING-LIB-C",
        title = "Mistborn",
        sort_title = null,
        subtitle = null,
        description = null,
        publish_year = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = 0L,
        explicit = 0L,
        has_scan_warning = 0L,
        total_duration = 1L,
        cover_source = null,
        cover_path = null,
        cover_hash = null,
        user_edited_fields = "",
        root_rel_path = "Sanderson/Mistborn-listenup",
        inode = null,
        scanned_at = now,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
}
