package com.calypsan.listenup.server.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

class BoundedTextTest :
    FunSpec({
        fun clientResponding(respondWith: suspend MockRequestHandleScope.() -> HttpResponseData) = HttpClient(MockEngine { respondWith() })

        test("a body under the ceiling is returned as UTF-8 text") {
            val client = clientResponding { respond("Brontë", HttpStatusCode.OK) }

            bodyAsTextBounded(client.get("https://x.test/"), maxBytes = 1024) shouldBe "Brontë"
        }

        test("a declared Content-Length over the ceiling is refused") {
            val client =
                clientResponding {
                    respond("a".repeat(17), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "17"))
                }

            bodyAsTextBounded(client.get("https://x.test/"), maxBytes = 16) shouldBe null
        }

        test("an undeclared-length body that streams past the ceiling is refused") {
            val client =
                clientResponding {
                    respond(ByteReadChannel("a".repeat(17).encodeToByteArray()), HttpStatusCode.OK, headersOf())
                }

            bodyAsTextBounded(client.get("https://x.test/"), maxBytes = 16) shouldBe null
        }

        test("a body exactly at the ceiling is returned") {
            val client = clientResponding { respond("a".repeat(16), HttpStatusCode.OK) }

            bodyAsTextBounded(client.get("https://x.test/"), maxBytes = 16) shouldBe "a".repeat(16)
        }
    })
