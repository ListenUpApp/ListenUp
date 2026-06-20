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
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (service, importId, store) = stageService(db, principal = rootPrincipal())

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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (service, importId, _) = stageService(db, principal = rootPrincipal())
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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (service, _, _) = stageService(db, principal = rootPrincipal())
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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (service, _, _) = stageService(db, principal = rootPrincipal())
                    val applied = service.apply(ImportId("does-not-exist"))
                    (applied as AppResult.Failure).error.shouldBeInstanceOf<ImportError.ImportNotFound>()
                }
            }
        }

        test("every method is denied for a non-admin caller") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (service, importId, _) = stageService(db, principal = memberPrincipal())

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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val (service, importId, _) = stageService(db, principal = memberPrincipal())
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
    db: Database,
    principal: PrincipalProvider,
): StagedService {
    val home = Files.createTempDirectory("abs-service-")
    val paths = ImportPaths(home).apply { ensureDirs() }
    val importId = ImportId("abs-service-test")
    Files.createDirectories(paths.dirFor(importId.value))
    buildSyntheticAbsDb(paths.absDbFor(importId.value))

    val libId = seedLibraryUser(db)
    transaction(db) { seedBooks(libId.value) }

    val store = ImportStore(paths)
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val repo = PlaybackPositionRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
    val statsRepo = UserStatsRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
    val listeningEventRepo = ListeningEventRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
    val statsBackfill = UserStatsBackfillService(sql = db.asSqlDatabase(), userStatsRepo = statsRepo)
    val analyzer =
        ImportAnalyzer(
            reader = AbsBackupReader(),
            store = store,
            paths = paths,
            bookMatcher = BookMatcher(db),
            userMatcher = UserMatcher(),
            libraryRegistry = LibraryRegistry(db),
            db = db,
        )
    val applier =
        ImportApplier(
            reader = AbsBackupReader(),
            store = store,
            paths = paths,
            playbackPositionRepository = repo,
            sessionConverter = SessionConverter(),
            listeningEventRepository = listeningEventRepo,
            statsBackfill = statsBackfill,
        )
    val service =
        ImportServiceImpl(
            store = store,
            analyzer = analyzer,
            applier = applier,
            validator = MappingValidator(db),
            eventBus = kotlinx.coroutines.flow.MutableSharedFlow(replay = 0, extraBufferCapacity = 64),
            principal = principal,
        )
    return StagedService(service, importId, store)
}

private suspend fun seedLibraryUser(db: Database): LibraryId {
    val libId = LibraryRegistry(db).currentLibrary()
    transaction(db) {
        UserEntity.new(LU_USER) {
            email = "simon@x.test"
            emailNormalized = "simon@x.test"
            passwordHash = "phc"
            role = UserRoleColumn.MEMBER
            displayName = "Simon"
            status = UserStatusColumn.ACTIVE
            createdAt = 1L
            updatedAt = 1L
        }
    }
    return libId
}

/** Seeds two ListenUp books matching the synthetic ABS items (kings by ASIN, mist by title). */
private fun seedBooks(libraryId: String) {
    val now = 1_730_000_000_000L
    BookTable.insert {
        it[id] = LU_KINGS
        it[BookTable.libraryId] = libraryId
        it[title] = "The Way of Kings"
        it[asin] = "B00ASIN001"
        it[rootRelPath] = "Sanderson/Way of Kings"
        it[totalDuration] = 1L
        it[scannedAt] = now
        it[revision] = 1L
        it[createdAt] = now
        it[updatedAt] = now
    }
    BookTable.insert {
        it[id] = LU_MIST
        it[BookTable.libraryId] = libraryId
        it[title] = "Mistborn"
        it[rootRelPath] = "Sanderson/Mistborn-listenup"
        it[totalDuration] = 1L
        it[scannedAt] = now
        it[revision] = 1L
        it[createdAt] = now
        it[updatedAt] = now
    }
}
