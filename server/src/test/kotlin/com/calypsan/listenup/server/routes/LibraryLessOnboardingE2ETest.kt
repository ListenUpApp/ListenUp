package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.scanner.AudioLibraryFixture
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/**
 * The capstone proof of library-less onboarding: a server that boots with NO
 * `scanner.libraryPath` (truly library-less — `bootstrapLibraries`, the always-mounted
 * routes, and the scanner background tasks all run with zero configured libraries) can,
 * in a single running process and with no restart:
 *
 *  1. report `needsSetup == true` (no folder yet),
 *  2. accept a wizard `addFolderToLibrary` call (`POST /api/v1/libraries/folders`),
 *  3. live-mount + scan the new folder via the already-running `ScanOrchestrator`,
 *  4. serve the scanned books over the always-loaded `/api/v1/sync/books` substrate.
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
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    // 1. Mint the ROOT/ADMIN bearer (also serves as the wizard's admin caller).
                    val adminToken = client.mintRootToken()

                    // 2. The library-admin surface is PRESENT on a library-less boot (not 404),
                    //    and reports that the server still needs onboarding (no folders yet).
                    val statusResponse = client.get("/api/v1/libraries/setup-status") { bearerAuth(adminToken) }
                    statusResponse.status shouldBe HttpStatusCode.OK
                    val status = statusResponse.body<SetupStatus>()
                    status.needsSetup shouldBe true

                    // 3. Wizard fetches THE library to confirm it exists (singleton model).
                    val libraryResponse = client.get("/api/v1/libraries") { bearerAuth(adminToken) }
                    libraryResponse.status shouldBe HttpStatusCode.OK
                    val library = libraryResponse.body<Library>()

                    // 4. Wizard adds a folder to THE library pointing at the temp dir.
                    val addFolderResponse =
                        client.post("/api/v1/libraries/folders") {
                            bearerAuth(adminToken)
                            contentType(ContentType.Application.Json)
                            setBody(AddFolderBody(path = libraryDir.toString()))
                        }
                    addFolderResponse.status shouldBe HttpStatusCode.Created

                    // 5. Kick the live scan via the already-mounted orchestrator.
                    val scanResponse = client.post("/api/v1/libraries/scan") { bearerAuth(adminToken) }
                    scanResponse.status shouldBe HttpStatusCode.Accepted

                    // 6. Await books on the always-loaded sync substrate. The scan is async,
                    //    so poll the books page until at least one book lands.
                    val bookCount =
                        withTimeout(SCAN_AWAIT_TIMEOUT_MS) {
                            var count = 0
                            while (count == 0) {
                                count = client.syncBookCount(adminToken)
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

private const val SCAN_AWAIT_TIMEOUT_MS = 20_000L
private const val POLL_INTERVAL_MS = 100L

/** Request body for `POST /api/v1/libraries/folders`. */
@Serializable
private data class AddFolderBody(val path: String)

private suspend fun HttpClient.mintRootToken(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

/** Reads `GET /api/v1/sync/books?since=0` and returns the number of book rows served. */
private suspend fun HttpClient.syncBookCount(token: String): Int {
    val text =
        get("/api/v1/sync/books?since=0") { bearerAuth(token) }
            .bodyAsText()
    val page = contractJson.decodeFromString(Page.serializer(BookSyncPayload.serializer()), text)
    return page.items.size
}
