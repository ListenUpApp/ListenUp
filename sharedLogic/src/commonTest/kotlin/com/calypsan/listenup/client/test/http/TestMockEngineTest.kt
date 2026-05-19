package com.calypsan.listenup.client.test.http

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies [testMockEngine] dispatches requests to path handlers, falls back with 404 for
 * unmatched paths, and exposes the resulting [HttpClient] for use in seam tests.
 */
class TestMockEngineTest {
    @Test
    fun dispatchesJsonResponseForRegisteredPath() =
        runTest {
            val client =
                HttpClient(
                    testMockEngine {
                        respondJson("/api/v1/books") { """{"id":"book-1","title":"Dune"}""" }
                    },
                )

            val response: HttpResponse = client.get("http://unit.test/api/v1/books")
            assertTrue(response.status.isSuccess(), "matched path must resolve to 2xx")
            assertEquals("""{"id":"book-1","title":"Dune"}""", response.bodyAsText())
        }

    @Test
    fun returns404ForUnregisteredPath() =
        runTest {
            val client =
                HttpClient(
                    testMockEngine {
                        respondJson("/api/v1/books") { "{}" }
                    },
                )

            assertFailsWith<AssertionError> {
                client.get("http://unit.test/api/v1/nonexistent")
            }
        }

    @Test
    fun respondJsonIncludesContentTypeHeader() =
        runTest {
            val client =
                HttpClient(
                    testMockEngine {
                        respondJson("/whoami") { """{"user":"alice"}""" }
                    },
                )

            val response: HttpResponse = client.get("http://unit.test/whoami")
            val contentType = response.headers["Content-Type"]
            assertTrue(
                contentType?.startsWith("application/json") == true,
                "respondJson must set Content-Type: application/json (was $contentType)",
            )
        }

    @Test
    fun respondStatusEmitsRequestedStatusCode() =
        runTest {
            val client =
                HttpClient(
                    testMockEngine {
                        respondStatus("/missing", HttpStatusCode.NotFound)
                    },
                )

            val response: HttpResponse = client.get("http://unit.test/missing")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
