package com.calypsan.listenup.server

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication

class InstanceRoutesTest :
    FunSpec({
        test("GET /api/v1/instance returns ServerInfo deserializable in commonMain") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val httpClient =
                    createClient {
                        install(ContentNegotiation) { json() }
                    }

                val response = httpClient.get("/api/v1/instance")
                response.status shouldBe HttpStatusCode.OK

                val info: ServerInfo = response.body()
                info.name shouldBe "ListenUp"
                info.apiVersion shouldBe "v1"
                info.version.isNotBlank() shouldBe true
            }
        }
    })
