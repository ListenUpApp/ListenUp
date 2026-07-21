package com.calypsan.listenup.server

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

class PluginSmokeTest :
    FunSpec({
        test("StatusPages returns a structured JSON 404 for unknown paths") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/this-path-does-not-exist")

                response.status shouldBe HttpStatusCode.NotFound
                response.headers["Content-Type"]?.let {
                    ContentType.parse(it).match(ContentType.Application.Json) shouldBe true
                }
                response.bodyAsText() shouldContain "\"error\""
            }
        }

        test("kotlinx.rpc round-trip works on CIO") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val rpcClient =
                    createClient {
                        install(WebSockets)
                        installKrpc()
                    }

                val service =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/public") {
                            rpcConfig { serialization { json() } }
                        }.withService<PingService>()

                val result = service.ping()
                result.shouldBeInstanceOf<AppResult.Success<String>>()
                result.data shouldBe "pong"
            }
        }
    })
