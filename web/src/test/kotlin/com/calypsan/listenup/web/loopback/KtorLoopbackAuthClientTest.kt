package com.calypsan.listenup.web.loopback

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json

class KtorLoopbackAuthClientTest :
    FunSpec({
        fun clientReturning(status: HttpStatusCode, body: String): KtorLoopbackAuthClient {
            val engine =
                MockEngine {
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val http =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(contractJson) }
                }
            return KtorLoopbackAuthClient(http)
        }

        test("login decodes a Success envelope into AppResult.Success") {
            val authSessionJson =
                """
                {"type":"Success","data":{"accessToken":"jwt","accessTokenExpiresAt":1,
                "refreshToken":"rt","refreshTokenExpiresAt":2,"sessionId":"sid",
                "user":{"id":"uid","email":"a@x","displayName":"A","role":"MEMBER",
                "status":"ACTIVE","createdAt":0}}}
                """.trimIndent()
            val client = clientReturning(HttpStatusCode.OK, authSessionJson)

            val result = client.login(LoginRequest("a@x", "password1"))

            val success = result.shouldBeInstanceOf<AppResult.Success<*>>()
            (success.data as com.calypsan.listenup.api.dto.auth.AuthSession).accessToken shouldBe AccessToken("jwt")
        }

        test("login decodes a Failure envelope into the typed AuthError") {
            val failureJson =
                """{"type":"Failure","error":{"type":"AuthError.InvalidCredentials","correlationId":"c1"}}"""
            val client = clientReturning(HttpStatusCode.Unauthorized, failureJson)

            val result = client.login(LoginRequest("a@x", "password1"))

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<AuthError.InvalidCredentials>()
        }

        test("serverInfo decodes a bare ServerInfo body (not an envelope)") {
            val infoJson =
                """{"name":"ListenUp","version":"0.0.1","apiVersion":"v1","setupRequired":true,
                "registrationPolicy":"OPEN","instanceId":"id1"}""".trimIndent()
            val client = clientReturning(HttpStatusCode.OK, infoJson)

            val result = client.serverInfo()

            val success = result.shouldBeInstanceOf<AppResult.Success<ServerInfo>>()
            success.data.setupRequired shouldBe true
            success.data.registrationPolicy shouldBe RegistrationPolicy.OPEN
        }

        test("serverInfo decodes a bare AppError on a non-2xx status into AppResult.Failure") {
            val client =
                clientReturning(
                    HttpStatusCode.InternalServerError,
                    """{"type":"AppError.InternalError","correlationId":"c1"}""",
                )

            val failure = client.serverInfo().shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<InternalError>()
        }

        test("a transport failure folds to InternalError") {
            val engine = MockEngine { throw java.io.IOException("connection refused") }
            val client =
                KtorLoopbackAuthClient(
                    HttpClient(engine) {
                        install(ContentNegotiation) { json(contractJson) }
                    },
                )

            val failure = client.serverInfo().shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<InternalError>()
        }
    })
