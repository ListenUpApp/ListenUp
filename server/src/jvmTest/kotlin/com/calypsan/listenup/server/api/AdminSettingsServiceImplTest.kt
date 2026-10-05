package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.server.hardcover.ADMIN_TOKEN
import com.calypsan.listenup.server.hardcover.FakeHardcoverCatalog
import com.calypsan.listenup.server.hardcover.HardcoverCatalogRig
import com.calypsan.listenup.server.hardcover.HardcoverSourceSettings
import com.calypsan.listenup.server.hardcover.NoWaitRateLimiter
import io.kotest.matchers.string.shouldNotContain
import com.calypsan.listenup.server.testing.shouldFailWith
import app.cash.turbine.test
import com.calypsan.listenup.api.dto.admin.AdminServerSettingsPatch
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.ExternalRatingMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.RatingSource
import com.calypsan.listenup.server.metadata.spi.RatingSourceAvailability
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import kotlin.time.Clock
import com.calypsan.listenup.server.ratings.RatingSourceSettings
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
import com.calypsan.listenup.server.sync.BookExternalRatingRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
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

        // (h) holdNewBooksForReview persists to the library and round-trips through getServerSettings
        test("updateServerSettings holdNewBooksForReview persists to the library and round-trips through getServerSettings") {
            withSqlDatabase {
                runTest {
                    val (svc, libraryRepository, libraryRegistry) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))

                    svc.getServerSettings().shouldSucceed().holdNewBooksForReview shouldBe false

                    svc.updateServerSettings(AdminServerSettingsPatch(holdNewBooksForReview = true)).shouldSucceed()
                    svc.getServerSettings().shouldSucceed().holdNewBooksForReview shouldBe true

                    libraryRepository.readHoldNewBooksForReview(libraryRegistry.currentLibrary()) shouldBe true
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

        // (l) getRatingSources lists every registered RatingSource with its enabled flag and health
        test("getRatingSources lists AUDIBLE, enabled by default with no health yet") {
            withSqlDatabase {
                runTest {
                    val sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.OPEN))
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                            sourceSettings = sourceSettings,
                            externalRatings = BookExternalRatingRepository(sql, ChangeBus(), SyncRegistry(), driver),
                            providerRegistry = singleRatingSourceRegistry(),
                        )

                    val sources = svc.getRatingSources().shouldSucceed()

                    sources shouldBe
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = true,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                        )
                }
            }
        }

        test("getRatingSources names the Hardcover account the Hardcover row borrows, and no one else's row") {
            withSqlDatabase {
                runTest {
                    val (hardcoverSource, rig) = hardcoverSourceFor(this@withSqlDatabase)
                    rig.connect("admin1", UserRoleColumn.ADMIN)
                    val sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.OPEN))
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                            sourceSettings = sourceSettings,
                            externalRatings = BookExternalRatingRepository(sql, ChangeBus(), SyncRegistry(), driver),
                            providerRegistry = singleRatingSourceRegistry(source = ExternalRatingSource.HARDCOVER),
                            hardcoverSource = hardcoverSource,
                        )

                    svc
                        .getRatingSources()
                        .shouldSucceed()
                        .single()
                        .connectionUsername shouldBe "hc-admin1"
                }
            }
        }

        test("getRatingSources reports a paused source's pausedUntil and an unavailable source's reason") {
            withSqlDatabase {
                runTest {
                    val sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.OPEN))
                    repeat(5) {
                        sourceSettings.recordFailure(ExternalRatingSource.AUDIBLE, "boom", Clock.System.now().toEpochMilliseconds())
                    }
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                            sourceSettings = sourceSettings,
                            externalRatings = BookExternalRatingRepository(sql, ChangeBus(), SyncRegistry(), driver),
                            providerRegistry =
                                singleRatingSourceRegistry(
                                    RatingSourceAvailability.Unavailable(RatingSourceUnavailable.NO_CONNECTION),
                                ),
                        )

                    val status = svc.getRatingSources().shouldSucceed().single()

                    (status.pausedUntil != null) shouldBe true
                    status.unavailable shouldBe RatingSourceUnavailable.NO_CONNECTION
                    status.connectionUsername shouldBe null
                }
            }
        }

        // (m) getRatingSources by a MEMBER is rejected with PermissionDenied
        test("getRatingSources by a MEMBER is rejected with PermissionDenied") {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("m1", UserRole.MEMBER),
                            sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.OPEN)),
                            externalRatings = BookExternalRatingRepository(sql, ChangeBus(), SyncRegistry(), driver),
                            providerRegistry = singleRatingSourceRegistry(),
                        )
                    svc.getRatingSources().shouldFail<AuthError.PermissionDenied>()
                }
            }
        }

        // (n) setRatingSourceEnabled(false) flips every row of that source AND the next getRatingSources reports it
        test("setRatingSourceEnabled(false) flips every row of that source and the next getRatingSources reports it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                sql.seedTestBook("book2", asin = "B002")
                runTest {
                    val ratingsRepo = BookExternalRatingRepository(sql, ChangeBus(), SyncRegistry(), driver)
                    ratingsRepo.recordFetch("book1", ExternalRatingSource.AUDIBLE, 4.2, 100, "us", fetchedAt = 1_000L)
                    ratingsRepo.recordFetch("book2", ExternalRatingSource.AUDIBLE, 3.9, 40, "us", fetchedAt = 1_000L)

                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                            sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.OPEN)),
                            externalRatings = ratingsRepo,
                            providerRegistry = singleRatingSourceRegistry(),
                        )

                    val updated = svc.setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, enabled = false).shouldSucceed()

                    updated shouldBe
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = false,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                        )
                    ratingsRepo.findForBook("book1").single().enabled shouldBe false
                    ratingsRepo.findForBook("book2").single().enabled shouldBe false

                    // Reported again by a fresh read, not just the returned value.
                    svc
                        .getRatingSources()
                        .shouldSucceed()
                        .single()
                        .enabled shouldBe false
                }
            }
        }

        // (o) setRatingSourceEnabled by a MEMBER is rejected with PermissionDenied, and flips nothing
        test("setRatingSourceEnabled by a MEMBER is rejected with PermissionDenied") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                runTest {
                    val ratingsRepo = BookExternalRatingRepository(sql, ChangeBus(), SyncRegistry(), driver)
                    ratingsRepo.recordFetch("book1", ExternalRatingSource.AUDIBLE, 4.2, 100, "us", fetchedAt = 1_000L)

                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("m1", UserRole.MEMBER),
                            sourceSettings = RatingSourceSettings(ServerSettingsRepository(sql, RegistrationPolicy.OPEN)),
                            externalRatings = ratingsRepo,
                            providerRegistry = singleRatingSourceRegistry(),
                        )

                    svc.setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, enabled = false).shouldFail<AuthError.PermissionDenied>()

                    ratingsRepo.findForBook("book1").single().enabled shouldBe true
                }
            }
        }

        test("an admin stores a Hardcover API token, and the status names its owner and never carries it") {
            withSqlDatabase {
                runTest {
                    val (hardcoverSource) = hardcoverSourceFor(this@withSqlDatabase)
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("admin1", UserRole.ADMIN),
                            hardcoverSource = hardcoverSource,
                        )

                    val saved = svc.setHardcoverApiToken(ADMIN_TOKEN).shouldSucceed()

                    (saved.apiToken as HardcoverApiTokenStatus.Saved).username shouldBe "simon"
                    svc.getHardcoverSource().shouldSucceed() shouldBe saved
                    saved.toString() shouldNotContain ADMIN_TOKEN
                }
            }
        }

        test("a token Hardcover refuses comes back as TokenRejected and nothing is stored") {
            withSqlDatabase {
                runTest {
                    val hardcover =
                        FakeHardcoverCatalog().apply {
                            accounts = mapOf(ADMIN_TOKEN to "simon")
                            rejected = setOf("hc_unknown_test_token")
                        }
                    val (hardcoverSource) = hardcoverSourceFor(this@withSqlDatabase, hardcover)
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                            hardcoverSource = hardcoverSource,
                        )

                    svc.setHardcoverApiToken("hc_unknown_test_token").shouldFailWith<HardcoverError.TokenRejected>()
                    svc.getHardcoverSource().shouldSucceed().apiToken shouldBe HardcoverApiTokenStatus.NotSet
                }
            }
        }

        test("Remove clears the token, and the metadata switch round-trips") {
            withSqlDatabase {
                runTest {
                    val (hardcoverSource) = hardcoverSourceFor(this@withSqlDatabase)
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                            hardcoverSource = hardcoverSource,
                        )
                    svc.setHardcoverApiToken(ADMIN_TOKEN).shouldSucceed()

                    svc.clearHardcoverApiToken().shouldSucceed().apiToken shouldBe HardcoverApiTokenStatus.NotSet
                    svc.setHardcoverMetadataEnabled(false).shouldSucceed().metadataEnabled shouldBe false
                    svc.getHardcoverSource().shouldSucceed().metadataEnabled shouldBe false
                }
            }
        }

        test("a member is denied every Hardcover source call, and nothing changes") {
            withSqlDatabase {
                runTest {
                    val (hardcoverSource) = hardcoverSourceFor(this@withSqlDatabase)
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("member1", UserRole.MEMBER),
                            hardcoverSource = hardcoverSource,
                        )

                    svc.getHardcoverSource().shouldFailWith<AuthError.PermissionDenied>()
                    svc.setHardcoverApiToken(ADMIN_TOKEN).shouldFailWith<AuthError.PermissionDenied>()
                    svc.clearHardcoverApiToken().shouldFailWith<AuthError.PermissionDenied>()
                    svc.setHardcoverMetadataEnabled(false).shouldFailWith<AuthError.PermissionDenied>()
                    hardcoverSource.status() shouldBe
                        HardcoverSourceStatus(
                            metadataUnavailable = com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable.NO_CONNECTION,
                        )
                }
            }
        }

        test("the store region defaults to the United States, persists, and reaches clients on the library payload") {
            withSqlDatabase {
                runTest {
                    val (svc, libraryRepository, libraryRegistry) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))

                    svc.getServerSettings().shouldSucceed().metadataRegion shouldBe "us"

                    svc.updateServerSettings(AdminServerSettingsPatch(metadataRegion = "UK")).shouldSucceed()

                    svc.getServerSettings().shouldSucceed().metadataRegion shouldBe "uk"
                    val libraryId = libraryRegistry.currentLibrary()
                    libraryRepository.readMetadataRegion(libraryId) shouldBe "uk"
                    libraryRepository.readPayloadForTest(libraryId.value)!!.metadataRegion shouldBe "uk"
                }
            }
        }

        test("a store that is not on the list is refused, and nothing changes") {
            withSqlDatabase {
                runTest {
                    val (svc) =
                        makeAdminSettingsService(
                            db = this@withSqlDatabase,
                            principal = principalFor("root1", UserRole.ROOT),
                        )
                    seedLibrary(this@withSqlDatabase, principalFor("root1", UserRole.ROOT))

                    svc
                        .updateServerSettings(AdminServerSettingsPatch(metadataRegion = "xx"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AdminError.InvalidInput>()
                    svc.getServerSettings().shouldSucceed().metadataRegion shouldBe "us"
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
    sourceSettings: RatingSourceSettings? = null,
    externalRatings: BookExternalRatingRepository? = null,
    providerRegistry: MetadataProviderRegistry? = null,
    hardcoverSource: HardcoverSourceSettings? = null,
): AdminSettingsFixture {
    val libraryRepo = LibraryRepository(db = db.sql, bus = bus, registry = SyncRegistry())
    val libraryRegistry = LibraryRegistry(sql = db.sql)
    val svc =
        AdminSettingsServiceImpl(
            settings = ServerSettingsRepository(db.sql, default = RegistrationPolicy.OPEN),
            changeBus = bus,
            libraryRegistry = libraryRegistry,
            libraryRepository = libraryRepo,
            sourceSettings = sourceSettings,
            externalRatings = externalRatings,
            providerRegistry = providerRegistry,
            hardcoverSource = hardcoverSource,
        ).copyWith(principal)
    return AdminSettingsFixture(svc, libraryRepo, libraryRegistry)
}

/** Real Hardcover source settings over [db], asking [hardcover] (which knows [ADMIN_TOKEN] as `simon`). */
private fun hardcoverSourceFor(
    db: SqlTestDatabases,
    hardcover: FakeHardcoverCatalog = FakeHardcoverCatalog().apply { accounts = mapOf(ADMIN_TOKEN to "simon") },
): Pair<HardcoverSourceSettings, HardcoverCatalogRig> {
    val rig = HardcoverCatalogRig(db.sql)
    return HardcoverSourceSettings(
        apiTokens = rig.apiTokens,
        catalogToken = rig.catalog,
        graphQl = hardcover.client(),
        rateLimiter = NoWaitRateLimiter(),
        settings = ServerSettingsRepository(db.sql, RegistrationPolicy.OPEN),
    ) to rig
}

/** A single-source (AUDIBLE) [MetadataProviderRegistry] — enough for [AdminSettingsServiceImpl]'s
 *  outside-ratings surface, which only needs [RatingSource]-capable providers. */
private fun singleRatingSourceRegistry(
    availability: RatingSourceAvailability = RatingSourceAvailability.Available,
    source: ExternalRatingSource = ExternalRatingSource.AUDIBLE,
): MetadataProviderRegistry =
    MetadataProviderRegistry(
        listOf(
            object : RatingSource {
                override val id: MetadataProviderId = MetadataProviderId.AUDIBLE
                override val ratingSource: ExternalRatingSource = source

                override suspend fun availability(): RatingSourceAvailability = availability

                override suspend fun getRating(
                    book: BookIdentity,
                    locale: MetadataLocale,
                    refresh: Boolean,
                ): AppResult<ExternalRatingMeta?> = AppResult.Success(null)
            },
        ),
    )

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
