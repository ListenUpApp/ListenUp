package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

private const val CONCURRENT_CALLERS = 16

/**
 * Unit tests for [RpcProxyCache] — the shared stateful body every post-login RPC
 * factory delegates to. Supersedes the earlier near-identical per-factory tests that
 * pinned this same Mutex-caching contract before the generalization; the invariant now
 * lives in exactly one place, so the test moves with it (`test_at_invariant_layer`).
 *
 * No subclassing is needed to seam this out: the `connect` lambda supplied to the
 * constructor IS the seam, so these tests exercise the real [RpcProxyCache] directly.
 *
 * The round-trip behavior (proxy → real server) stays covered by the jvmTest E2E
 * suite ([com.calypsan.listenup.client.books.BooksEndToEndTest] and siblings).
 */
class RpcProxyCacheTest :
    FunSpec({

        /**
         * Builds a [RpcProxyCache] whose [ServerConfig] resolves to [activeUrl] and
         * whose [ApiClientFactory] hands back a `MockEngine`-backed [HttpClient] that
         * never actually sends a request (the `connect` stub below never calls `rpc()`).
         * `connectCalls`/`clientCalls` count invocations of the constructor args so
         * concurrency and invalidation behavior can be pinned precisely.
         *
         * The single at-most-once lease path is reached through `call { it }` — the identity block
         * forces exactly one [RpcProxyCache.lease] (connect/reuse) and hands the leased proxy back,
         * the smallest drive of the connect/single-flight/URL/not-configured behavior now that the
         * bare-proxy `get()` accessor is gone (raw-proxy reach is a compile error by design).
         */
        fun fixture(
            activeUrl: String? = "http://server.local:8080",
            connectCalls: MutableList<String> = mutableListOf(),
        ): Pair<RpcProxyCache<Any>, ApiClientFactory> {
            val serverConfig = mock<ServerConfig>()
            everySuspend { serverConfig.getActiveUrl() } returns activeUrl?.let { ServerUrl(it) }

            val apiClientFactory = mock<ApiClientFactory>()
            everySuspend { apiClientFactory.getClient() } returns
                HttpClient(MockEngine { respondOk() })

            val cache =
                RpcProxyCache(apiClientFactory, serverConfig) { _, wsBaseUrl ->
                    connectCalls += wsBaseUrl
                    Any()
                }
            return cache to apiClientFactory
        }

        test("call leases the proxy produced by connect") {
            runTest {
                var produced: Any? = null
                val serverConfig = mock<ServerConfig>()
                everySuspend { serverConfig.getActiveUrl() } returns ServerUrl("http://server.local:8080")
                val apiClientFactory = mock<ApiClientFactory>()
                everySuspend { apiClientFactory.getClient() } returns HttpClient(MockEngine { respondOk() })
                val cache =
                    RpcProxyCache(apiClientFactory, serverConfig) { _, _ ->
                        Any().also { produced = it }
                    }

                val proxy = cache.call { it }

                proxy shouldBe produced
            }
        }

        test("second call leases the same instance; connect invoked exactly once") {
            runTest {
                val connectCalls = mutableListOf<String>()
                val (cache, _) = fixture(connectCalls = connectCalls)

                val first = cache.call { it }
                val second = cache.call { it }

                (first === second) shouldBe true
                connectCalls.size shouldBe 1
            }
        }

        test("16 concurrent calls resolve to the same instance (Mutex single-flight)") {
            runTest {
                val connectCalls = mutableListOf<String>()
                val (cache, _) = fixture(connectCalls = connectCalls)

                val proxies = (1..CONCURRENT_CALLERS).map { async { cache.call { it } } }.awaitAll()

                proxies.toSet().size shouldBe 1
                connectCalls.size shouldBe 1
            }
        }

        test("invalidate then call reconnects: new instance, connect called again, client re-derived") {
            runTest {
                val connectCalls = mutableListOf<String>()
                val (cache, apiClientFactory) = fixture(connectCalls = connectCalls)

                val first = cache.call { it }
                cache.invalidate()
                val second = cache.call { it }

                (first === second) shouldBe false
                connectCalls.size shouldBe 2
                verifySuspend(exactly(2)) { apiClientFactory.getClient() }
            }
        }

        test("wsBaseUrl handed to connect is the WebSocket-scheme conversion of the configured URL") {
            runTest {
                val httpsCalls = mutableListOf<String>()
                val (httpsCache, _) = fixture(activeUrl = "https://server.local:8080", connectCalls = httpsCalls)
                httpsCache.call { it }
                httpsCalls shouldBe listOf("wss://server.local:8080")

                val httpCalls = mutableListOf<String>()
                val (httpCache, _) = fixture(activeUrl = "http://server.local:8080", connectCalls = httpCalls)
                httpCache.call { it }
                httpCalls shouldBe listOf("ws://server.local:8080")
            }
        }

        test("no active URL: call throws ServerUrlNotConfiguredException, client never derived") {
            runTest {
                val (cache, apiClientFactory) = fixture(activeUrl = null)

                shouldThrow<ServerUrlNotConfiguredException> { cache.call { it } }

                verifySuspend(exactly(0)) { apiClientFactory.getClient() }
            }
        }
    })
