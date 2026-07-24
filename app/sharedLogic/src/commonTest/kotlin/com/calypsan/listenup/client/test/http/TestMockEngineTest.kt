package com.calypsan.listenup.client.test.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.runTest

/**
 * Verifies [testMockEngine] dispatches requests to path handlers, falls back with 404 for
 * unmatched paths, and exposes the resulting [HttpClient] for use in seam tests.
 */
class TestMockEngineTest :
    FunSpec({
        test("dispatchesJsonResponseForRegisteredPath") {
            runTest {
                val client =
                    HttpClient(
                        testMockEngine {
                            respondJson("/api/v1/books") { """{"id":"book-1","title":"Dune"}""" }
                        },
                    )

                val response: HttpResponse = client.get("http://unit.test/api/v1/books")
                withClue("matched path must resolve to 2xx") { response.status.isSuccess() shouldBe true }
                response.bodyAsText() shouldBe """{"id":"book-1","title":"Dune"}"""
            }
        }

        test("returns404ForUnregisteredPath") {
            runTest {
                val client =
                    HttpClient(
                        testMockEngine {
                            respondJson("/api/v1/books") { "{}" }
                        },
                    )

                shouldThrow<AssertionError> {
                    client.get("http://unit.test/api/v1/nonexistent")
                }
            }
        }

        test("respondJsonIncludesContentTypeHeader") {
            runTest {
                val client =
                    HttpClient(
                        testMockEngine {
                            respondJson("/whoami") { """{"user":"alice"}""" }
                        },
                    )

                val response: HttpResponse = client.get("http://unit.test/whoami")
                val contentType = response.headers["Content-Type"]
                withClue("respondJson must set Content-Type: application/json (was $contentType)") {
                    (contentType?.startsWith("application/json") == true) shouldBe true
                }
            }
        }

        test("respondStatusEmitsRequestedStatusCode") {
            runTest {
                val client =
                    HttpClient(
                        testMockEngine {
                            respondStatus("/missing", HttpStatusCode.NotFound)
                        },
                    )

                val response: HttpResponse = client.get("http://unit.test/missing")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
