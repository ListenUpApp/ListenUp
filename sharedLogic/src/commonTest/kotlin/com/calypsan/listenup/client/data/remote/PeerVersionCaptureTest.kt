package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.VersionHeaders
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest

/**
 * Pins [installPeerVersionCapture]'s debounce contract: the server stamps
 * `X-Server-Version`/`X-Server-Api` on every response (see the server's Task 10 retrofit), so a
 * naive "call the hook on every response" would hammer secure storage with a redundant write per
 * request. Only a CHANGED (version, api) pair forwards to the callback.
 */
class PeerVersionCaptureTest :
    FunSpec({
        fun engineFor(
            version: String,
            api: String,
        ) = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers =
                    headersOf(
                        HttpHeaders.ContentType to listOf("text/plain"),
                        VersionHeaders.SERVER_VERSION to listOf(version),
                        VersionHeaders.SERVER_API to listOf(api),
                    ),
            )
        }

        test("forwards the peer version + api on the first response") {
            runTest {
                val captured = mutableListOf<Pair<String, String>>()
                val client =
                    HttpClient(engineFor("1.2.3", "v1")) {
                        installPeerVersionCapture { version, api -> captured += version to api }
                    }

                client.get("/ping")

                captured shouldBe listOf("1.2.3" to "v1")
            }
        }

        test("does not fire again when a repeat response carries the same pair") {
            runTest {
                val captured = mutableListOf<Pair<String, String>>()
                val client =
                    HttpClient(engineFor("1.2.3", "v1")) {
                        installPeerVersionCapture { version, api -> captured += version to api }
                    }

                client.get("/ping")
                client.get("/ping")
                client.get("/ping")

                captured shouldBe listOf("1.2.3" to "v1")
            }
        }

        test("fires again when the peer version changes") {
            runTest {
                val captured = mutableListOf<Pair<String, String>>()
                var version = "1.2.3"
                val engine =
                    MockEngine { _ ->
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType to listOf("text/plain"),
                                    VersionHeaders.SERVER_VERSION to listOf(version),
                                    VersionHeaders.SERVER_API to listOf("v1"),
                                ),
                        )
                    }
                val client =
                    HttpClient(engine) {
                        installPeerVersionCapture { v, api -> captured += v to api }
                    }

                client.get("/ping")
                version = "1.3.0"
                client.get("/ping")

                captured shouldBe listOf("1.2.3" to "v1", "1.3.0" to "v1")
            }
        }

        test("does not fire when the response carries no server-version headers") {
            runTest {
                val captured = mutableListOf<Pair<String, String>>()
                val engine = MockEngine { _ -> respond(content = "", status = HttpStatusCode.OK) }
                val client =
                    HttpClient(engine) {
                        installPeerVersionCapture { version, api -> captured += version to api }
                    }

                client.get("/ping")

                captured shouldBe emptyList()
            }
        }
    })
