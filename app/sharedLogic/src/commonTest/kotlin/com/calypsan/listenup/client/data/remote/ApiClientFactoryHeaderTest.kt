package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.VersionHeaders
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.client.test.http.testMockEngine
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest

/**
 * Pins the outbound version-announcement headers (see [com.calypsan.listenup.client.domain.version.ClientIdentity])
 * that let the server evaluate compat/skew — `ConnectionHealthStore` on the client and the
 * server's `ClientVersionMetrics` both depend on these being present on every request.
 */
class ApiClientFactoryHeaderTest :
    FunSpec({
        test("getClient stamps X-Client-Version and X-Client-Api on every outbound request") {
            runTest {
                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getActiveUrl() } returns ServerUrl("https://server.example.com")
                    }
                var captured: HttpRequestData? = null
                val engine =
                    testMockEngine {
                        handle("/ping") {
                            captured = it
                            respondOk()
                        }
                    }
                val authSession =
                    mock<AuthSession> {
                        everySuspend { getAccessToken() } returns null
                        everySuspend { getRefreshToken() } returns null
                    }
                val factory =
                    KtorApiClientFactory(
                        serverConfig = serverConfig,
                        authSession = authSession,
                        refreshAccessToken = { error("token refresh not used") },
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                        engine = engine,
                    )

                factory.getClient().get("/ping")

                // Asserted against the concrete wire values (not just the constants) as a
                // belt-and-suspenders contract check: the send side uses VersionHeaders, so this
                // pins that "X-Client-Version"/"X-Client-Api" is what actually lands on the wire.
                captured?.headers?.get(VersionHeaders.CLIENT_VERSION) shouldBe "0.6.0"
                captured?.headers?.get(VersionHeaders.CLIENT_API) shouldBe "v1"
                captured?.headers?.get("X-Client-Version") shouldBe "0.6.0"
                captured?.headers?.get("X-Client-Api") shouldBe "v1"
            }
        }
    })
