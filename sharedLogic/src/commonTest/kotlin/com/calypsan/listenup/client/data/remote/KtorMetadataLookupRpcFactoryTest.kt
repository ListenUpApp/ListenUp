package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [KtorMetadataLookupRpcFactory]. Verifies the Mutex-caching contract:
 *  - [MetadataLookupRpcFactory.metadataLookupService] returns a non-null proxy
 *  - A second call returns the same instance (cached, per [Mutex] contract)
 *  - 16 concurrent calls all resolve to the same instance (Mutex correctness)
 *
 * [KtorMetadataLookupRpcFactory] is tested via a test-only anonymous subclass that
 * overrides [KtorMetadataLookupRpcFactory.connect] to return a stub service — the
 * minimum override needed to exercise the Mutex logic without network infrastructure.
 *
 * This is the canonical approach per the `test_at_invariant_layer` principle: the
 * invariant (Mutex caching) lives in the Ktor impl; that's where the test belongs.
 *
 * The round-trip behaviour (proxy → real server) is covered by the jvmTest E2E
 * suite ([com.calypsan.listenup.client.books.BooksEndToEndTest] and siblings).
 *
 * No [KtorContributorRpcFactoryTest] existed as a precedent; this is the first
 * factory unit test in the codebase. Future factory tests should mirror this file.
 */
class KtorMetadataLookupRpcFactoryTest :
    FunSpec({

        /**
         * A subclass that short-circuits [connect] so no Ktor client or running server is needed.
         * [ApiClientFactory] and [ServerConfig] are never accessed because the overridden
         * [connect] bypasses the Ktor wiring entirely.
         */
        fun factory(): KtorMetadataLookupRpcFactory {
            val stubService = mock<MetadataLookupService>()
            return object : KtorMetadataLookupRpcFactory(
                apiClientFactory = mock<ApiClientFactory>(),
                serverConfig = mock<ServerConfig>(),
            ) {
                override suspend fun connect(): MetadataLookupService = stubService
            }
        }

        test("metadataLookupService returns a non-null MetadataLookupService proxy") {
            runTest {
                val proxy = factory().metadataLookupService()
                (proxy is MetadataLookupService) shouldBe true
            }
        }

        test("second call returns the same instance (proxy is cached)") {
            runTest {
                val f = factory()
                val first = f.metadataLookupService()
                val second = f.metadataLookupService()
                (first === second) shouldBe true
            }
        }

        test("16 concurrent calls all return the same instance (Mutex correctness)") {
            runTest {
                val f = factory()
                val proxies = (1..16).map { async { f.metadataLookupService() } }.awaitAll()
                proxies.toSet().size shouldBe 1
            }
        }
    })
