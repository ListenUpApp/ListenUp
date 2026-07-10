package com.calypsan.listenup.client.e2e

import com.calypsan.listenup.api.VersionHeaders
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.di.e2e.DiWiredClientFixture
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.statement.request
import kotlinx.coroutines.test.runTest

/**
 * End-to-end proof of the version-exchange loop (Phase 2b Task 14) against the REAL `:server`,
 * in-process: client → server → client.
 *
 * [DiWiredClientFixture] boots the actual production `Application.module()` — which installs
 * `installVersionHeaders()` on every response (`server/plugins/VersionHeaders.kt`) — and the
 * full production client Koin graph, so [ApiClientFactory] and [AuthRepository] resolved here
 * are the exact production-wired instances: `KtorApiClientFactory` carrying
 * [com.calypsan.listenup.client.domain.version.DefaultClientIdentity] and
 * `installPeerVersionCapture` → `LocalPreferences::setPeerServerVersion` (`di/NetworkModule.kt`).
 *
 * `ClientVersionMetrics` (the server-side per-version request counter) is `internal` to `:server`
 * and is NOT visible across the Gradle module boundary from `:sharedLogic:jvmTest` — confirmed by
 * a failed compile probe, not assumed. The CLIENT→SERVER direction is instead asserted on the
 * materialized [io.ktor.client.request.HttpRequest] Ktor actually put on the wire
 * (`HttpResponse.request`, not a reconstruction) for a real loopback request against the real
 * server; the server-side half of that contract — that `installVersionHeaders()` reads this exact
 * header into `ClientVersionMetrics` — is independently pinned by `:server`'s own
 * `VersionHeadersTest`.
 */
class ConnectionResilienceE2ETest :
    FunSpec({

        test("the real client and server exchange version headers, and the client persists the peer version") {
            runTest {
                val fixture = autoClose(DiWiredClientFixture.start())
                val koin = fixture.koin.koin
                val localPreferences = koin.get<LocalPreferences>()

                // No peer server observed yet — the server starts empty and no request has landed.
                localPreferences.peerServerVersion.value.shouldBeNull()

                // A real, anonymous REST request through the real KtorApiClientFactory against the
                // real server's InstanceRoutes.
                val apiClientFactory = koin.get<ApiClientFactory>()
                val response = apiClientFactory.getClient().get("/api/v1/instance")

                // CLIENT → SERVER: KtorApiClientFactory.createClient()'s defaultRequest block
                // attaches X-Client-Version/X-Client-Api to every request. Asserted here on the
                // actual materialized request Ktor sent (HttpResponse.request), not a
                // reconstruction.
                response.request.headers[VersionHeaders.CLIENT_VERSION] shouldBe "0.6.0"
                response.request.headers[VersionHeaders.CLIENT_API] shouldBe "v1"

                // SERVER → CLIENT: installVersionHeaders() stamped X-Server-Version/X-Server-Api
                // on the real response.
                response.headers[VersionHeaders.SERVER_VERSION] shouldBe "0.6.0"
                response.headers[VersionHeaders.SERVER_API] shouldBe "v1"

                // The real payoff of the Task 8-12 arc: installPeerVersionCapture read those
                // response headers off and onPeerVersion persisted them into LocalPreferences.
                localPreferences.peerServerVersion.value shouldBe "0.6.0"
                localPreferences.peerServerApi.value shouldBe "v1"
            }
        }

        test("a real authenticated RPC round trip also carries and persists the peer server version") {
            runTest {
                val fixture = autoClose(DiWiredClientFixture.start())
                val koin = fixture.koin.koin

                val authRepository = koin.get<AuthRepository>()
                val localPreferences = koin.get<LocalPreferences>()

                // Provision + log in through the DI-resolved repo, against the live server — the
                // same first-run path production takes, over the kotlinx.rpc WebSocket transport
                // (a different code path from the plain REST GET above).
                val credentials =
                    RegisterRequest(
                        email = "e2e-version@listenup.app",
                        password = "e2e-password",
                        displayName = "E2E Version User",
                    )
                authRepository.setup(credentials).shouldBeInstanceOf<AppResult.Success<*>>()
                authRepository
                    .login(LoginRequest(email = credentials.email, password = credentials.password))
                    .shouldBeInstanceOf<AppResult.Success<*>>()

                // The RPC WebSocket upgrade response also carries X-Server-Version/X-Server-Api,
                // and installPeerVersionCapture persists them the same way as a plain REST call.
                localPreferences.peerServerVersion.value shouldBe "0.6.0"
                localPreferences.peerServerApi.value shouldBe "v1"
            }
        }
    })
