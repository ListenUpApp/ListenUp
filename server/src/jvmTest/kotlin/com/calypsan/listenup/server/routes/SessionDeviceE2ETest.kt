package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.testing.publicAuthService

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.server.testing.authedService

/**
 * End-to-end proof of the device-management vertical, exercised through the
 * real public auth routes in-process: two devices belonging to the same user
 * log in with distinct [DeviceInfo], the session list surfaces both, and
 * revoking one device kills its refresh-token rotation while the surviving
 * device keeps rotating.
 */
class SessionDeviceE2ETest :
    FunSpec({

        suspend fun ApplicationTestBuilder.seedRoot() {
            publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }

        suspend fun ApplicationTestBuilder.loginAs(deviceModel: String): AuthSession =
            publicAuthService()
                .login(
                    LoginRequest(
                        email = "root@x",
                        password = "x".repeat(8),
                        deviceInfo = DeviceInfo(deviceModel = deviceModel),
                    ),
                ).shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data

        test("two devices log in, list shows both, revoking one kills its refresh while the other survives") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }

                seedRoot()

                val s1 = loginAs("Pixel 10")
                val s2 = loginAs("iPad")

                val sessions =
                    authedService<AuthServiceAuthed>(s1.accessToken.value)
                        .listSessions()
                        .shouldBeInstanceOf<AppResult.Success<List<SessionSummary>>>()
                        .data
                sessions.mapNotNull { it.deviceInfo?.deviceModel } shouldContainAll listOf("Pixel 10", "iPad")

                authedService<AuthServiceAuthed>(s1.accessToken.value)
                    .revokeSession(s2.sessionId)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val revokedRefresh =
                    publicAuthService().refreshSession(RefreshRequest(s2.refreshToken))
                revokedRefresh
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.InvalidRefreshToken>()

                val survivingRefresh =
                    publicAuthService().refreshSession(RefreshRequest(s1.refreshToken))
                survivingRefresh
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
            }
        }
    })
