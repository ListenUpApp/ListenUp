package com.calypsan.listenup.server.api

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.scanner.ScanCoordinator
import com.calypsan.listenup.server.scanner.ScanOrchestrator
import com.calypsan.listenup.server.scanner.ScannerBundle
import com.calypsan.listenup.server.scanner.ScannerResultPort
import com.calypsan.listenup.server.scanner.WatcherSupervisorPort
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.GlobalScope
import kotlinx.io.files.Path
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [AdminSettingsServiceImpl].
 *
 * Real in-memory Flyway-migrated SQLite + real [ServerSettingsRepository]; no mocks.
 * The acting caller is supplied via a [PrincipalProvider] stub; [principalFor] binds
 * the service to a chosen `(userId, role)`.
 */
class AdminSettingsServiceImplTest :
    FunSpec({

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider =
            PrincipalProvider {
                UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
            }

        // (a) getServerSettings returns defaults for ROOT
        test("getServerSettings returns default server name and null remoteUrl when unset") {
            withSqlDatabase {
                runTest {
                    val (svc, _, libraryRegistry) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))
                    val settings = svc.getServerSettings().shouldSucceed()
                    settings.serverName shouldBe ServerIdentity.NAME
                    settings.remoteUrl.shouldBeNull()
                }
            }
        }

        // (b) updateServerSettings persists name+remoteUrl and a fresh read reflects it
        test("updateServerSettings persists serverName and remoteUrl for ADMIN and fresh read reflects the change") {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("a1", UserRole.ADMIN),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))
                    val updated =
                        svc
                            .updateServerSettings(
                                AdminServerSettingsPatch(serverName = "My Server", remoteUrl = "https://example.com"),
                            ).shouldSucceed()
                    updated.serverName shouldBe "My Server"
                    updated.remoteUrl shouldBe "https://example.com"

                    // fresh read via getServerSettings reflects the persisted values
                    val fresh = svc.getServerSettings().shouldSucceed()
                    fresh.serverName shouldBe "My Server"
                    fresh.remoteUrl shouldBe "https://example.com"
                }
            }
        }

        // (c) MEMBER caller → AuthError.PermissionDenied on getServerSettings
        test("getServerSettings by a MEMBER is rejected with PermissionDenied") {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("m1", UserRole.MEMBER),
                        )
                    svc.getServerSettings().shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        // (d) blank serverName → AdminError.InvalidInput
        test("updateServerSettings with a blank serverName returns InvalidInput") {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))
                    svc
                        .updateServerSettings(AdminServerSettingsPatch(serverName = "   "))
                        .shouldFail<AdminError.InvalidInput>()
                }
            }
        }

        // (e) empty remoteUrl clears it
        test("updateServerSettings with empty remoteUrl clears the stored remote URL") {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))

                    // first set a URL
                    svc.updateServerSettings(AdminServerSettingsPatch(remoteUrl = "https://example.com")).shouldSucceed()
                    val withUrl = svc.getServerSettings().shouldSucceed()
                    withUrl.remoteUrl shouldBe "https://example.com"

                    // then clear it with an empty string
                    svc.updateServerSettings(AdminServerSettingsPatch(remoteUrl = "")).shouldSucceed()
                    val cleared = svc.getServerSettings().shouldSucceed()
                    cleared.remoteUrl.shouldBeNull()
                }
            }
        }

        // (f) a successful change broadcasts a content-free ServerInfoChanged nudge to all clients
        test("updateServerSettings broadcasts ServerInfoChanged on a successful change") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            bus = bus,
                            principal = principalFor("a1", UserRole.ADMIN),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))

                    bus.subscribeControl().test {
                        svc.updateServerSettings(AdminServerSettingsPatch(remoteUrl = "https://new.example.com")).shouldSucceed()
                        val frame = awaitItem()
                        frame.control shouldBe SyncControl.ServerInfoChanged
                        frame.userId shouldBe ChangeBus.BROADCAST
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }

        // (g) a no-op patch (no fields) writes nothing and broadcasts nothing
        test("updateServerSettings with an empty patch broadcasts no nudge") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            bus = bus,
                            principal = principalFor("a1", UserRole.ADMIN),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))

                    bus.subscribeControl().test {
                        svc.updateServerSettings(AdminServerSettingsPatch()).shouldSucceed()
                        expectNoEvents()
                    }
                }
            }
        }

        // (h) inboxEnabled persists to the library and round-trips through getServerSettings
        test("updateServerSettings inboxEnabled persists to the library and round-trips through getServerSettings") {
            withSqlDatabase {
                runTest {
                    val (svc, libraryRepository, libraryRegistry) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))

                    svc.getServerSettings().shouldSucceed().inboxEnabled shouldBe false

                    svc.updateServerSettings(AdminServerSettingsPatch(inboxEnabled = true)).shouldSucceed()
                    svc.getServerSettings().shouldSucceed().inboxEnabled shouldBe true

                    libraryRepository.readInboxEnabled(libraryRegistry.currentLibrary()) shouldBe true
                }
            }
        }

        // (i) pushNotificationsEnabled defaults to true and round-trips through getServerSettings
        test(
            "updateServerSettings pushNotificationsEnabled defaults true and round-trips through getServerSettings",
        ) {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))

                    svc.getServerSettings().shouldSucceed().pushNotificationsEnabled shouldBe true

                    svc.updateServerSettings(AdminServerSettingsPatch(pushNotificationsEnabled = false)).shouldSucceed()
                    svc.getServerSettings().shouldSucceed().pushNotificationsEnabled shouldBe false
                }
            }
        }

        // (j) a pushNotificationsEnabled change broadcasts ServerInfoChanged
        test("updateServerSettings broadcasts ServerInfoChanged on a pushNotificationsEnabled change") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            bus = bus,
                            principal = principalFor("a1", UserRole.ADMIN),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("a1", UserRole.ADMIN))

                    bus.subscribeControl().test {
                        svc.updateServerSettings(AdminServerSettingsPatch(pushNotificationsEnabled = false)).shouldSucceed()
                        val frame = awaitItem()
                        frame.control shouldBe SyncControl.ServerInfoChanged
                        frame.userId shouldBe ChangeBus.BROADCAST
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }

        // (k) sidecarWritesEnabled defaults to true and round-trips through the settings KV store
        test("updateServerSettings sidecarWritesEnabled defaults true, persists, and round-trips") {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))

                    // Absent key = enabled (spec: sidecar writes are on by default).
                    svc.getServerSettings().shouldSucceed().sidecarWritesEnabled shouldBe true

                    svc.updateServerSettings(AdminServerSettingsPatch(sidecarWritesEnabled = false)).shouldSucceed()
                    svc.getServerSettings().shouldSucceed().sidecarWritesEnabled shouldBe false

                    svc.updateServerSettings(AdminServerSettingsPatch(sidecarWritesEnabled = true)).shouldSucceed()
                    svc.getServerSettings().shouldSucceed().sidecarWritesEnabled shouldBe true
                }
            }
        }
    })

// ── Test fixtures ─────────────────────────────────────────────────────────────

private data class AdminSettingsFixture(
    val service: AdminSettingsServiceImpl,
    val libraryRepository: LibraryRepository,
    val libraryRegistry: LibraryRegistry,
)

private fun makeAdminSettingsService(
    db: SqlTestDatabases,
    bus: ChangeBus = ChangeBus(),
    principal: PrincipalProvider,
): AdminSettingsFixture {
    val libraryRepo = LibraryRepository(db = db.sql, bus = bus, registry = SyncRegistry())
    val libraryRegistry = LibraryRegistry(sql = db.sql)
    val svc =
        AdminSettingsServiceImpl(
            settings = ServerSettingsRepository(db.sql, default = RegistrationPolicy.OPEN),
            changeBus = bus,
            libraryRegistry = libraryRegistry,
            libraryRepository = libraryRepo,
        ).copyWith(principal)
    return AdminSettingsFixture(svc, libraryRepo, libraryRegistry)
}

/**
 * Seeds the single library for tests that invoke [AdminSettingsServiceImpl.getServerSettings]
 * or [AdminSettingsServiceImpl.updateServerSettings] (both call [LibraryRegistry.currentLibrary]
 * which requires at least one live library row in the DB).
 */
private suspend fun seedLibrary(
    db: SqlTestDatabases,
    principal: PrincipalProvider,
) {
    val bus = ChangeBus()
    val libraryRepo = LibraryRepository(db = db.sql, bus = bus, registry = SyncRegistry())
    val folderRepo =
        LibraryFolderRepository(
            db = db.sql,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            driver = db.driver,
        )
    val contributorRepo = ContributorRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry())
    val seriesRepo = SeriesRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry())
    val bookRepo =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = ChangeBus(),
            registry = SyncRegistry(),
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository =
                com.calypsan.listenup.server.services.GenreRepository(
                    db = db.sql,
                    bus = ChangeBus(),
                    registry = SyncRegistry(),
                ),
        )
    val dir = Files.createTempDirectory("listenup-seed-").toFile().apply { deleteOnExit() }
    LibraryAdminServiceImpl(
        libraryRepository = libraryRepo,
        libraryFolderRepository = folderRepo,
        bookRepository = bookRepo,
        scanOrchestrator = noOpOrchestrator(),
        libraryRegistry = LibraryRegistry(sql = db.sql),
    ).copyWith(principal)
        .addFolder(dir.absolutePath)
}

private fun noOpOrchestrator(): ScanOrchestrator =
    ScanOrchestrator(
        scannerFactory = { library ->
            val coordinator =
                ScanCoordinator(
                    libraryId = library.id,
                    runFullScan = {
                        ScanResult(
                            correlationId = "test",
                            rootPath = library.folders.firstOrNull()?.rootPath ?: "/tmp",
                            books = emptyList(),
                            changes = emptyList(),
                            errors = emptyList(),
                            durationMs = 0,
                            filesWalked = 0,
                            filesSkipped = 0,
                            scope = ScanScope.Full,
                        )
                    },
                    runIncremental = {},
                    scope = GlobalScope,
                )
            ScannerBundle(
                library = library,
                scanner =
                    object : ScannerResultPort {
                        override fun lastResult(): ScanResult? = null

                        override fun markSuperseded() = Unit
                    },
                coordinator = coordinator,
            )
        },
        watcherSupervisor =
            object : WatcherSupervisorPort {
                override suspend fun mount(
                    libraryId: LibraryId,
                    folder: com.calypsan.listenup.api.dto.LibraryFolderRef,
                    onEvent: suspend (LibraryId, Path) -> Unit,
                ) = Unit

                override suspend fun unmount(folderId: FolderId) = Unit

                override suspend fun unmountAllForLibrary(libraryId: LibraryId) = Unit

                override suspend fun unmountAll() = Unit
            },
    )

private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data

private inline fun <reified E : AppError> AppResult<*>.shouldFail(): E =
    shouldBeInstanceOf<AppResult.Failure>()
        .error
        .shouldBeInstanceOf<E>()
