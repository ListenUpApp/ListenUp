package com.calypsan.listenup.server.routes

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.server.testing.publicAuthService

import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.scanner.AudioLibraryFixture
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.shouldSucceed
import io.kotest.matchers.types.shouldBeInstanceOf
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder

/**
 * The capstone proof of library-less onboarding: a server that boots with NO
 * `scanner.libraryPath` (truly library-less — `bootstrapLibraries`, the always-mounted
 * routes, and the scanner background tasks all run with zero configured libraries) can,
 * in a single running process and with no restart:
 *
 *  1. report `needsSetup == true` (no folder yet),
 *  2. accept a wizard `LibraryAdminService.addFolder` call,
 *  3. live-mount + scan the new folder via the already-running `ScanOrchestrator`,
 *  4. serve the scanned books over the always-loaded `SyncStreamService` `books` domain.
 *
 * This is the end-to-end guarantee that the unconditional-module / unconditional-route
 * boot rework actually closes the loop — if books never appear after a scan, the
 * live-mount wiring has a real gap, not a test flake.
 *
 * Runs in Kotest's plain (non-`runTest`) scope: the scan is real background work and the
 * await is a wall-clock-bounded poll, so virtual time is the wrong tool.
 */
class LibraryLessOnboardingE2ETest :
    FunSpec({

        test("library-less server: wizard adds a folder, scans it live, books appear — no restart") {
            // A temp library dir with one real (placeholder) book the scanner can ingest.
            // The scanner E2E proves zero-byte placeholder tracks group into a book.
            val libraryDir = Files.createTempDirectory("listenup-onboarding-e2e-lib-")
            AudioLibraryFixture(libraryDir).apply {
                book("Brandon Sanderson/The Way of Kings") {
                    tracks(count = 2)
                    cover()
                }
            }

            try {
                testApplication {
                    useIsolatedTestConfig() // NO scanner.libraryPath → library-less boot
                    application { module() }

                    // 1. Mint the ROOT/ADMIN bearer (also serves as the wizard's admin caller).
                    val adminToken = mintRootToken()

                    // 2. The library-admin surface is PRESENT on a library-less boot (not 404),
                    //    and reports that the server still needs onboarding (no folders yet).
                    val status =
                        authedService<LibraryAdminService>(adminToken)
                            .getSetupStatus()
                            .shouldBeInstanceOf<AppResult.Success<SetupStatus>>()
                            .data
                    status.needsSetup shouldBe true

                    // 3. Wizard fetches THE library to confirm it exists (singleton model).
                    authedService<LibraryAdminService>(adminToken)
                        .getLibrary()
                        .shouldBeInstanceOf<AppResult.Success<Library>>()

                    // 4. Wizard adds a folder to THE library pointing at the temp dir.
                    authedService<LibraryAdminService>(adminToken)
                        .addFolder(libraryDir.toString())
                        .shouldBeInstanceOf<AppResult.Success<LibraryFolder>>()

                    // 5. Kick the live scan via the already-mounted orchestrator.
                    //    Library bootstrap — which registers the library with the orchestrator and warms
                    //    its scanner bundle (Application.kt `scope.launch { bootstrapLibraries(...) }`) —
                    //    runs ASYNCHRONOUSLY at startup. A scan triggered before that registration lands
                    //    races it and returns 404 (the library isn't known to the orchestrator yet), so we
                    //    poll the trigger until accepted. Registration is monotonic — once the bundle exists
                    //    it stays — so this converges; only the startup-race 404 is tolerated, any other
                    //    status fails fast. (In production the wizard runs long after boot, never racing.)
                    withTimeout(SCAN_TRIGGER_TIMEOUT_MS) {
                        while (true) {
                            val scanResult = authedService<LibraryAdminService>(adminToken).scanLibrary()
                            if (scanResult is AppResult.Success) break
                            // Only the startup-race "library not yet registered" failure is tolerated.
                            scanResult.shouldBeInstanceOf<AppResult.Failure>()
                            delay(POLL_INTERVAL_MS)
                        }
                    }

                    // 6. Await books on the always-loaded sync substrate. The scan is async,
                    //    so poll the books pull until at least one book lands.
                    val bookCount =
                        withTimeout(SCAN_AWAIT_TIMEOUT_MS) {
                            var count = 0
                            while (count == 0) {
                                count = syncBookCount(adminToken)
                                if (count == 0) delay(POLL_INTERVAL_MS)
                            }
                            count
                        }

                    // 7. Books appeared: library-less boot → onboard → live scan → served, no restart.
                    bookCount shouldBeGreaterThanOrEqual 1
                }
            } finally {
                libraryDir.toFile().deleteRecursively()
            }
        }
    })

private const val SCAN_TRIGGER_TIMEOUT_MS = 15_000L
private const val SCAN_AWAIT_TIMEOUT_MS = 20_000L
private const val POLL_INTERVAL_MS = 100L

/** Request body for `POST /api/v1/libraries/folders`. */

private suspend fun ApplicationTestBuilder.mintRootToken(): String =
    publicAuthService()
        .setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

/** Pulls the `books` sync domain since the beginning and returns the number of rows served. */
private suspend fun ApplicationTestBuilder.syncBookCount(token: String): Int =
    authedService<SyncStreamService>(token)
        .pullDomain("books", since = 0, limit = 500)
        .shouldSucceed()
        .items
        .size
