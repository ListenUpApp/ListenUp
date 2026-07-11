package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.test.runTest

/**
 * Pins the streaming-client lifecycle that keeps the SSE firehose alive across a reconnect sweep.
 *
 * [ApiClientFactory.invalidateRequestClientOnly] must drop the request client (so RPC proxies
 * rebind) while leaving the cached streaming client untouched — closing it would abort the very SSE
 * read whose reconnect triggered the sweep, spinning a self-teardown loop. The full [ApiClientFactory.invalidate]
 * (URL/identity change) still rebuilds everything, streaming included.
 */
class ApiClientFactoryStreamingCacheTest :
    FunSpec({

        fun factory(): KtorApiClientFactory {
            val serverConfig =
                mock<ServerConfig> {
                    everySuspend { getActiveUrl() } returns ServerUrl("https://server.example.com")
                }
            // MockEngine backs the request client; the streaming client builds its real (idle) engine.
            // No request is ever issued, so the handler is never invoked.
            val engine = MockEngine { respondOk() }
            return KtorApiClientFactory(
                serverConfig = serverConfig,
                authSession = mock<AuthSession>(),
                refreshAccessToken = { error("token refresh not used") },
                clientIdentity = FakeClientIdentity(),
                engine = engine,
            )
        }

        test("invalidateRequestClientOnly rebuilds the request client but preserves the streaming client") {
            runTest {
                val factory = factory()
                val streaming1 = factory.getStreamingClient()
                val request1 = factory.getClient()

                factory.invalidateRequestClientOnly()

                // The streaming client — the one the SSE firehose rides — is the SAME instance.
                factory.getStreamingClient() shouldBeSameInstanceAs streaming1
                // The request client was dropped, so a fresh one is built.
                factory.getClient() shouldNotBeSameInstanceAs request1
            }
        }

        test("full invalidate rebuilds the streaming client too") {
            runTest {
                val factory = factory()
                val streaming1 = factory.getStreamingClient()

                factory.invalidate()

                // A genuine URL/identity change must repoint the streaming client — new instance.
                factory.getStreamingClient() shouldNotBeSameInstanceAs streaming1
            }
        }
    })
