package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.data.repository.SettingsRepositoryImpl
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class ApiClientFactoryFallbackTest :
    FunSpec({
        test("executeWithFallback re-throws CancellationException from the fallback retry, not the original error") {
            runTest {
                val serverConfig = mock<ServerConfig>()
                everySuspend { serverConfig.switchToFallbackUrl() } returns ServerUrl("https://fallback.example.com")
                val factory =
                    KtorApiClientFactory(
                        serverConfig,
                        mock<AuthSession>(),
                        { error("token refresh not used") },
                        FakeClientIdentity(),
                    )

                var attempts = 0
                val request = HttpRequestBuilder().apply { url("https://primary.example.com/api") }

                // A network error on the primary triggers the fallback; the fallback is then cancelled.
                // The cancellation must propagate, not be swallowed as the original network error.
                shouldThrow<CancellationException> {
                    factory.executeWithFallback(request) {
                        attempts++
                        if (attempts == 1) throw IOException("primary unreachable")
                        throw CancellationException("fallback cancelled")
                    }
                }
            }
        }

        // Real settings repository + real client over a host-routing MockEngine: the LAN host is
        // unreachable, the WAN host records whatever reaches it.
        suspend fun roamingClient(
            allowFallback: Boolean,
            reachedWan: MutableList<HttpRequestData>,
        ): io.ktor.client.HttpClient {
            val storage = FallbackMapStorage()
            storage.save("server_url", "http://192.168.1.20:8080")
            storage.save("server_remote_url", "https://wan.example.com")
            val settings =
                SettingsRepositoryImpl(
                    secureStorage = storage,
                    authSession = lazy<AuthSession> { error("auth is not used by the fallback switch") },
                    allowsFallback = { _, _ -> allowFallback },
                )
            val authSession =
                mock<AuthSession> {
                    everySuspend { getAccessToken() } returns AccessToken("access-token")
                    everySuspend { getRefreshToken() } returns RefreshToken("refresh-token")
                }
            val engine =
                MockEngine { request ->
                    if (request.url.host == "192.168.1.20") throw IOException("LAN unreachable")
                    reachedWan += request
                    respondOk()
                }
            return KtorApiClientFactory(
                serverConfig = settings,
                authSession = authSession,
                refreshAccessToken = { error("token refresh not used") },
                clientIdentity = FakeClientIdentity(),
                engine = engine,
            ).getClient()
        }

        test("a refused fallback never receives a request, so never receives the bearer") {
            runTest {
                val reachedWan = mutableListOf<HttpRequestData>()
                val client = roamingClient(allowFallback = false, reachedWan = reachedWan)

                shouldThrow<IOException> { client.get("/api/v1/ping") }

                reachedWan.shouldBeEmpty()
                client.close()
            }
        }

        test("an allowed fallback receives the retried request with the bearer") {
            runTest {
                val reachedWan = mutableListOf<HttpRequestData>()
                val client = roamingClient(allowFallback = true, reachedWan = reachedWan)

                client.get("/api/v1/ping")

                reachedWan.size shouldBe 1
                reachedWan.single().headers[HttpHeaders.Authorization] shouldBe "Bearer access-token"
                client.close()
            }
        }
    })

private class FallbackMapStorage : SecureStorage {
    private val store = mutableMapOf<String, String>()

    override suspend fun save(
        key: String,
        value: String,
    ) {
        store[key] = value
    }

    override suspend fun read(key: String): String? = store[key]

    override suspend fun delete(key: String) {
        store.remove(key)
    }

    override suspend fun clear() {
        store.clear()
    }
}
