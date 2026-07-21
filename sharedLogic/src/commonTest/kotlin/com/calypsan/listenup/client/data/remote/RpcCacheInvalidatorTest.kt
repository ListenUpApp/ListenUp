package com.calypsan.listenup.client.data.remote

import io.ktor.client.HttpClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins how [DefaultRpcCacheInvalidator] dispatches its two sweeps:
 *
 *  - [RpcCacheInvalidator.invalidateAll] drops EVERY cache with a full [RemoteCache.invalidate] —
 *    including the [ApiClientFactory]'s streaming client. Correct for a URL/identity change.
 *  - [RpcCacheInvalidator.invalidateRequestCaches] drops the RPC proxy caches with a full
 *    `invalidate()` but routes the [ApiClientFactory] through the scoped
 *    [ApiClientFactory.invalidateRequestClientOnly] so the SSE streaming client survives — the fix
 *    for the firehose self-teardown loop.
 */
class RpcCacheInvalidatorTest :
    FunSpec({

        /** Records which invalidation entry point was hit, without touching a real HttpClient. */
        class RecordingApiClientFactory : ApiClientFactory {
            var fullInvalidations = 0
            var requestOnlyInvalidations = 0

            override suspend fun getClient(): HttpClient = error("not used")

            override suspend fun warmUp() = Unit

            override suspend fun invalidate() {
                fullInvalidations++
            }

            override suspend fun invalidateRequestClientOnly() {
                requestOnlyInvalidations++
            }
        }

        class RecordingRpcCache : RemoteCache {
            var invalidations = 0

            override suspend fun invalidate() {
                invalidations++
            }
        }

        test("invalidateAll closes the streaming client (full invalidate) on the ApiClientFactory") {
            runTest {
                val apiClient = RecordingApiClientFactory()
                val rpcCache = RecordingRpcCache()
                val invalidator = DefaultRpcCacheInvalidator(caches = listOf(apiClient, rpcCache))

                invalidator.invalidateAll()

                apiClient.fullInvalidations shouldBe 1
                apiClient.requestOnlyInvalidations shouldBe 0
                rpcCache.invalidations shouldBe 1
            }
        }

        test("invalidateRequestCaches spares the streaming client but still sweeps RPC proxy caches") {
            runTest {
                val apiClient = RecordingApiClientFactory()
                val rpcCache = RecordingRpcCache()
                val invalidator = DefaultRpcCacheInvalidator(caches = listOf(apiClient, rpcCache))

                invalidator.invalidateRequestCaches()

                // ApiClientFactory takes the scoped path — the streaming client is NOT closed.
                apiClient.requestOnlyInvalidations shouldBe 1
                apiClient.fullInvalidations shouldBe 0
                // Every other RemoteCache (the RPC proxy caches) still gets a full invalidate so the
                // next RPC call rebinds to the live connection.
                rpcCache.invalidations shouldBe 1
            }
        }
    })
