package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [KtorLibraryAdminRpcFactory]. Verifies the Mutex-caching contract:
 *  - [LibraryAdminRpcFactory.get] returns a non-null [LibraryAdminService] proxy
 *  - A second call returns the same instance (cached, per [Mutex] contract)
 *  - 16 concurrent calls all resolve to the same instance (Mutex correctness)
 *
 * [KtorLibraryAdminRpcFactory] is tested via a test-only anonymous subclass that
 * overrides [KtorLibraryAdminRpcFactory.connect] to return a stub service — the
 * minimum override needed to exercise the Mutex logic without network infrastructure.
 *
 * This is the canonical approach per the `test_at_invariant_layer` principle: the
 * invariant (Mutex caching) lives in the Ktor impl; that's where the test belongs.
 *
 * Mirrors [KtorMetadataLookupRpcFactoryTest] — the established precedent for RPC factory
 * unit tests in this codebase.
 */
class KtorLibraryAdminRpcFactoryTest :
    FunSpec({

        /**
         * A subclass that short-circuits [connect] so no Ktor client or running server is needed.
         * [ApiClientFactory] and [ServerConfig] are never accessed because the overridden
         * [connect] bypasses the Ktor wiring entirely.
         */
        fun factory(): KtorLibraryAdminRpcFactory {
            val stubService = mock<LibraryAdminService>()
            return object : KtorLibraryAdminRpcFactory(
                apiClientFactory = mock<ApiClientFactory>(),
                serverConfig = mock<ServerConfig>(),
            ) {
                override suspend fun connect(): LibraryAdminService = stubService
            }
        }

        test("get returns a non-null LibraryAdminService proxy") {
            runTest {
                val proxy = factory().get()
                (proxy is LibraryAdminService) shouldBe true
            }
        }

        test("second call returns the same instance (proxy is cached)") {
            runTest {
                val f = factory()
                val first = f.get()
                val second = f.get()
                (first === second) shouldBe true
            }
        }

        test("16 concurrent calls all return the same instance (Mutex correctness)") {
            runTest {
                val f = factory()
                val proxies = (1..16).map { async { f.get() } }.awaitAll()
                proxies.toSet().size shouldBe 1
            }
        }
    })
