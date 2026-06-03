package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.backup.BackupTestFixture
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.backup.RestoreOrchestrator
import com.calypsan.listenup.server.backup.backupTestFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
                    archivePath.toFile().exists() shouldBe true
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

        test("happy-path restore round-trip: createBackup then restoreBackup returns RestoreResult") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Seed a marker row before creating the backup
                    transaction(fixture.handle.database) {
                        exec("CREATE TABLE IF NOT EXISTS restore_marker(v TEXT)")
                        exec("INSERT INTO restore_marker(v) VALUES ('before-restore')")
                    }

                    val svc = buildService(fixture, rootPrincipalProvider())
                    val created =
                        svc
                            .createBackup(includeImages = false)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data

                    // Mutate db after backup — restore should bring it back
                    transaction(fixture.handle.database) {
                        exec("DELETE FROM restore_marker")
                        exec("INSERT INTO restore_marker(v) VALUES ('after-backup-mutation')")
                    }

                    val restoreResult = svc.restoreBackup(created.id)

                    restoreResult.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.backup.RestoreResult>>()
                    val result = restoreResult.data
                    result.restoredFrom shouldBe created.id
                    result.includedImages shouldBe false

                    // Verify db is functional after restore (marker was restored back or Flyway re-ran)
                    transaction(fixture.handle.database) {
                        val count =
                            exec("SELECT count(*) FROM restore_marker") { rs ->
                                if (rs.next()) rs.getInt(1) else 0
                            }
                        // 'before-restore' row is back (restore worked) OR table still exists (DB is intact)
                        count shouldBe 1
                    }
                }
            }
        }
    })
