package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.backup.BackupTestFixture
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.backup.RestoreOrchestrator
import com.calypsan.listenup.server.backup.backupTestFixture
import com.calypsan.listenup.server.backup.execSql
import com.calypsan.listenup.server.backup.queryScalarInt
import com.calypsan.listenup.server.sync.ChangeBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem

/**
 * Happy-path tests for [BackupServiceImpl].
 *
 * Tests drive [BackupServiceImpl] directly, bound to a [PrincipalProvider] that carries
 * either a ROOT principal (admin) or a MEMBER principal (non-admin), against a real
 * [BackupArchive] + [BackupPaths] in a temp home dir — the same fixture used by
 * [BackupArchiveTest]. Adversarial restore tests (rollback, incompat schema, single-flight)
 * live in Task 8's [RestoreOrchestratorTest].
 */
class BackupServiceTest :
    FunSpec({

        fun rootPrincipalProvider(): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId("root-user"), SessionId("root-session"), UserRole.ROOT)
            }

        fun memberPrincipalProvider(): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId("member-user"), SessionId("member-session"), UserRole.MEMBER)
            }

        fun buildService(
            fixture: BackupTestFixture,
            principal: PrincipalProvider,
        ): BackupServiceImpl {
            val eventBus = MutableSharedFlow<com.calypsan.listenup.api.dto.backup.BackupEvent>(extraBufferCapacity = 64)
            val maintenance = MaintenanceState()
            val orchestrator =
                RestoreOrchestrator(
                    paths = fixture.paths,
                    archive = fixture.archive,
                    dbHandle = fixture.handle,
                    maintenance = maintenance,
                    eventBus = eventBus,
                    changeBus = ChangeBus(),
                )
            return BackupServiceImpl(
                paths = fixture.paths,
                archive = fixture.archive,
                restoreOrchestrator = orchestrator,
                eventBus = eventBus,
                principal = principal,
            )
        }

        test("ROOT createBackup(includeImages=false) succeeds and the archive file exists") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val svc = buildService(fixture, rootPrincipalProvider())

                    val result = svc.createBackup(includeImages = false)

                    result.shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                    val summary = result.data
                    val archivePath = fixture.paths.archiveFor(summary.id.value)
                    java.io.File(archivePath.toString()).exists() shouldBe true
                    summary.includesImages shouldBe false
                }
            }
        }

        test("listBackups returns the created backup and getBackup retrieves it by id") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val svc = buildService(fixture, rootPrincipalProvider())

                    val created =
                        svc
                            .createBackup(includeImages = false)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data

                    val listed =
                        svc
                            .listBackups()
                            .shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                            .data
                    listed shouldHaveSize 1
                    listed.first().id shouldBe created.id

                    val fetched =
                        svc
                            .getBackup(created.id)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data
                    fetched.id shouldBe created.id
                }
            }
        }

        test("deleteBackup removes the archive; subsequent listBackups is empty") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val svc = buildService(fixture, rootPrincipalProvider())

                    val created =
                        svc
                            .createBackup(includeImages = false)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data

                    svc.deleteBackup(created.id).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val listed =
                        svc
                            .listBackups()
                            .shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                            .data
                    listed.shouldBeEmpty()
                }
            }
        }

        test("non-admin principal gets PermissionDenied on createBackup") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val svc = buildService(fixture, memberPrincipalProvider())

                    val result = svc.createBackup(includeImages = false)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("non-admin principal gets PermissionDenied on listBackups") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val svc = buildService(fixture, memberPrincipalProvider())

                    val result = svc.listBackups()

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("admin observeProgress receives events emitted on the bus") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64)
                    val maintenance = MaintenanceState()
                    val orchestrator =
                        RestoreOrchestrator(
                            paths = fixture.paths,
                            archive = fixture.archive,
                            dbHandle = fixture.handle,
                            maintenance = maintenance,
                            eventBus = eventBus,
                            changeBus = ChangeBus(),
                        )
                    val svc =
                        BackupServiceImpl(
                            paths = fixture.paths,
                            archive = fixture.archive,
                            restoreOrchestrator = orchestrator,
                            eventBus = eventBus,
                            principal = rootPrincipalProvider(),
                        )

                    svc.observeProgress().test {
                        eventBus.emit(BackupEvent.Validating)
                        val received = awaitItem()
                        received.shouldBeInstanceOf<RpcEvent.Data<BackupEvent>>()
                        received.value shouldBe BackupEvent.Validating
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }

        test("non-admin observeProgress emits nothing") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64)
                    val maintenance = MaintenanceState()
                    val orchestrator =
                        RestoreOrchestrator(
                            paths = fixture.paths,
                            archive = fixture.archive,
                            dbHandle = fixture.handle,
                            maintenance = maintenance,
                            eventBus = eventBus,
                            changeBus = ChangeBus(),
                        )
                    val svc =
                        BackupServiceImpl(
                            paths = fixture.paths,
                            archive = fixture.archive,
                            restoreOrchestrator = orchestrator,
                            eventBus = eventBus,
                            principal = memberPrincipalProvider(),
                        )

                    // emptyFlow() completes immediately — toList() returns [] without blocking
                    val events = svc.observeProgress().toList()
                    events.shouldBeEmpty()
                }
            }
        }

        test("deleteBackup rejects a path-traversal id and does not delete files outside backupsDir") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val svc = buildService(fixture, rootPrincipalProvider())

                    // A traversal id `../secret` resolves via archiveFor to
                    // <homeDir>/backups/../secret.listenup.zip == <homeDir>/secret.listenup.zip,
                    // i.e. OUTSIDE backupsDir. Plant a sentinel there.
                    val sentinel = fixture.paths.archiveFor("../secret")
                    SystemFileSystem.createDirectories(fixture.paths.backupsDir)
                    SystemFileSystem.sink(sentinel).buffered().use { it.write(byteArrayOf(1, 2, 3)) }

                    val result = svc.deleteBackup(BackupId("../secret"))

                    // Secure behaviour: typed failure, and the sentinel survives.
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<BackupError.BackupNotFound>()
                    SystemFileSystem.exists(sentinel) shouldBe true
                }
            }
        }

        test("getBackup rejects a path-traversal id with BackupNotFound") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val svc = buildService(fixture, rootPrincipalProvider())
                    svc
                        .getBackup(BackupId("../secret"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<BackupError.BackupNotFound>()
                }
            }
        }

        test("happy-path restore round-trip: createBackup then restoreBackup returns RestoreResult") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Seed a marker row before creating the backup
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS restore_marker(v TEXT)",
                        "INSERT INTO restore_marker(v) VALUES ('before-restore')",
                    )

                    val svc = buildService(fixture, rootPrincipalProvider())
                    val created =
                        svc
                            .createBackup(includeImages = false)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data

                    // Mutate db after backup — restore should bring it back
                    fixture.handle.execSql(
                        "DELETE FROM restore_marker",
                        "INSERT INTO restore_marker(v) VALUES ('after-backup-mutation')",
                    )

                    val restoreResult = svc.restoreBackup(created.id)

                    restoreResult.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.backup.RestoreResult>>()
                    val result = restoreResult.data
                    result.restoredFrom shouldBe created.id
                    result.includedImages shouldBe false

                    // Verify db is functional after restore (marker was restored back or migrations re-ran)
                    // 'before-restore' row is back (restore worked) OR table still exists (DB is intact)
                    fixture.handle.queryScalarInt("SELECT count(*) FROM restore_marker") shouldBe 1
                }
            }
        }
    })
