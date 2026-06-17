package com.calypsan.listenup.server.openapi

import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class OpenApiAuthDriftTest :
    FunSpec({
        val authPaths =
            listOf(
                "/api/v1/auth/login",
                "/api/v1/auth/register",
                "/api/v1/auth/setup",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
                "/api/v1/auth/logout/all",
                "/api/v1/auth/current-user",
                "/api/v1/auth/sessions",
                "/api/v1/auth/sessions/{id}",
            )

        test("the generated OpenAPI document describes every auth path") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val body = client.get("/api/openapi.json").bodyAsText()
                val paths = Json.parseToJsonElement(body).jsonObject["paths"]!!.jsonObject

                authPaths.forEach { path ->
                    (path in paths.keys) shouldBe true
                }
            }
        }

        test("Swagger UI is served at /api/docs") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val response = client.get("/api/docs")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "swagger"
            }
        }
    })
