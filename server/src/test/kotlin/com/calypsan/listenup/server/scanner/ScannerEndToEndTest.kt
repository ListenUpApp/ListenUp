package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.delay

/**
 * End-to-end scanner tests — real `Application.module()` over a CIO embedded
 * server, real DI graph, real Walker → Grouper → Analyzer → Differ pipeline,
 * real REST + Resources surface. The contract boundary is exercised in full.
 *
 * Following Phase 1's F12 pattern: NO test-only overrides on the production
 * graph — failures here mean the production wiring is broken.
 *
 * Coverage:
 *  - A triggered scan picks up every book in the library
 *  - lastScanResult returns the latest result with all fields populated
 *  - metadata.json overlay precedence end-to-end (server reads sidecar,
 *    folder fields are overridden, wire round-trips correctly)
 *  - POST /api/v1/scan returns Success after the library is registered
 *
 * No auto-scan on boot (Task 18): the server does NOT scan at startup. Tests
 * trigger a scan explicitly via POST /api/v1/scan and then poll for the
 * result. This removes the race between the bootstrap scan and test assertions.
 *
 * The `AlreadyRunning` failure mode is exercised at the unit level in
 * `ScanCoordinatorTest`; reproducing it reliably here requires fragile
 * timing, so we don't bother — the wire-level concern is covered by the
 * contract tests in `:shared`.
 *
 * These cases run in Kotest's plain test scope — NOT `runTest`. They make
 * real socket I/O against a real embedded server, so virtual time is the
 * wrong tool: Ktor 3.5.0's `HttpTimeout` plugin launches its timeout coroutine
 * in the *caller's* context (KTOR issue #4720 fix), so under `runTest` the
 * request-timeout's `delay` rides the auto-advancing virtual clock and fires
 * instantly — before the real network round-trip can complete. Real I/O tests
 * use real time; `AuthEndToEndTest` is the reference. The polling helper
 * bounds itself with a wall-clock deadline.
 */
class ScannerEndToEndTest :
    FunSpec({

        test("triggered scan picks up books and lastScanResult returns them") {
            val fix =
                autoClose(
                    ScannerEndToEndFixture.start {
                        book("Sanderson/Stormlight/The Way of Kings") {
                            tracks(count = 2)
                            cover()
                        }
                        book("Sanderson/Mistborn/The Final Empire") { tracks(count = 1) }
                    },
                )

            val result = triggerScanAndWait(fix)

            result.books.size shouldBe 2
            val titles = result.books.map { it.title }
            titles.contains("The Way of Kings") shouldBe true
            titles.contains("The Final Empire") shouldBe true
        }

        test("metadata.json overrides folder-derived fields end-to-end") {
            val fix =
                autoClose(
                    ScannerEndToEndFixture.start {
                        book("Author/Folder Title") {
                            tracks(count = 1)
                            metadataJson(
                                """
                                {
                                  "title": "Override Title",
                                  "authors": ["Brandon Sanderson"]
                                }
                                """.trimIndent(),
                            )
                        }
                    },
                )

            val result = triggerScanAndWait(fix)
            val book = result.books.single()

            book.title shouldBe "Override Title"
            book.authors shouldBe listOf("Brandon Sanderson")
        }

        test("triggering a manual scan returns Success") {
            val fix =
                autoClose(
                    ScannerEndToEndFixture.start {
                        book("Author/Title") { tracks(count = 1) }
                    },
                )

            val result = triggerScanAndWait(fix)
            result.books.size shouldBe 1
        }
    })

/**
 * Triggers a scan via POST /api/v1/scan (retrying until the library is
 * registered with the orchestrator, then polls GET /api/v1/scan/last until
 * a result is available.
 *
 * No auto-scan on boot (Task 18): `bootstrapLibraries` registers the library
 * with the orchestrator but does not scan. This helper bridges the gap for
 * tests that need a populated scan result.
 */
private suspend fun triggerScanAndWait(
    fix: ScannerEndToEndFixture,
    timeoutMs: Long = 15_000,
): ScanResult {
    val deadline = System.currentTimeMillis() + timeoutMs

    // Step 1: POST /scan until the library is registered (not AlreadyRunning means the orchestrator
    // knows the library; we need at least one Success response to confirm scanning happened).
    while (System.currentTimeMillis() < deadline) {
        val postBody = fix.client.post("${fix.baseUrl}/api/v1/scan").bodyAsAppResult<ScanResultSummary>()
        if (postBody is AppResult.Success) break
        delay(100)
    }

    // Step 2: Poll GET /scan/last until a result is available.
    while (System.currentTimeMillis() < deadline) {
        val response = fix.client.get("${fix.baseUrl}/api/v1/scan/last")
        val body = response.bodyAsAppResult<ScanResult>()
        if (body is AppResult.Success) return body.data
        delay(50)
    }
    error("scan did not complete within ${timeoutMs}ms")
}

private suspend inline fun <reified T : Any> HttpResponse.bodyAsAppResult(): AppResult<T> {
    val text: String = body()
    return contractJson.decodeFromString(AppResult.serializer(serializer<T>()), text)
}

private inline fun <reified T : Any> serializer(): kotlinx.serialization.KSerializer<T> = kotlinx.serialization.serializer()
