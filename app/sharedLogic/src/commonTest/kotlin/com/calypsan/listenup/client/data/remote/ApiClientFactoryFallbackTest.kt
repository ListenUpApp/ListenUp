package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
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
    })
