package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end scanner tests — real `Application.module()` over a CIO embedded
 * server, real DI graph, real Walker → Grouper → Analyzer → Differ pipeline,
 * real REST + Resources surface. The contract boundary is exercised in full.
 *
 * Following Phase 1's F12 pattern: NO test-only overrides on the production
 * graph — failures here mean the production wiring is broken.
 *
 * Coverage:
 *  - Initial scan picks up every book at startup
 *  - lastScanResult returns the latest result with all fields populated
 *  - metadata.json overlay precedence end-to-end (server reads sidecar,
 *    folder fields are overridden, wire round-trips correctly)
 *  - Manual POST /api/v1/scan after bootstrap returns Success
 *
 * The `AlreadyRunning` failure mode is exercised at the unit level in
 * `ScanCoordinatorTest`; reproducing it reliably here requires fragile
 * timing against the bootstrap scan, so we don't bother — the wire-level
 * concern (`AppResult.Failure(ScanError.AlreadyRunning)` round-trips
 * through serialization) is covered by the contract tests in `:shared`.
 */
class ScannerEndToEndTest :
    FunSpec({

        test("initial scan picks up books and lastScanResult returns them") {
            runTest(timeout = 30.seconds) {
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

                val result = waitForLastScanResult(fix)

                result.books.size shouldBe 2
                val titles = result.books.map { it.title }
                titles.contains("The Way of Kings") shouldBe true
                titles.contains("The Final Empire") shouldBe true
            }
        }

        test("metadata.json overrides folder-derived fields end-to-end") {
            runTest(timeout = 30.seconds) {
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

                val result = waitForLastScanResult(fix)
                val book = result.books.single()

                book.title shouldBe "Override Title"
                book.authors shouldBe listOf("Brandon Sanderson")
            }
        }

        test("triggering a manual scan after bootstrap completes returns Success") {
            runTest(timeout = 30.seconds) {
                val fix =
                    autoClose(
                        ScannerEndToEndFixture.start {
                            book("Author/Title") { tracks(count = 1) }
                        },
                    )

                // Wait for bootstrap to finish so we have a stable state.
                waitForLastScanResult(fix)

                val response = fix.client.post("${fix.baseUrl}/api/v1/scan")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsAppResult<ScanResultSummary>()
                val success = body.shouldBeInstanceOf<AppResult.Success<ScanResultSummary>>()
                success.data.totalBooks shouldBe 1
            }
        }
    })

/**
 * Polls `GET /api/v1/scan/last` until it returns Success — the bootstrap
 * scan kicked off by `Application.module()` runs asynchronously and tests
 * need to wait for it before asserting on results.
 */
private suspend fun waitForLastScanResult(
    fix: ScannerEndToEndFixture,
    timeoutMs: Long = 10_000,
): ScanResult {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val response = fix.client.get("${fix.baseUrl}/api/v1/scan/last")
        val body = response.bodyAsAppResult<ScanResult>()
        if (body is AppResult.Success) return body.data
        delay(50)
    }
    error("bootstrap scan did not complete within ${timeoutMs}ms")
}

private suspend inline fun <reified T : Any> HttpResponse.bodyAsAppResult(): AppResult<T> {
    val text: String = body()
    return contractJson.decodeFromString(AppResult.serializer(serializer<T>()), text)
}

private inline fun <reified T : Any> serializer(): kotlinx.serialization.KSerializer<T> = kotlinx.serialization.serializer()
