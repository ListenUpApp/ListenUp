package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.local.db.ServerDao
import com.calypsan.listenup.client.data.local.db.ServerEntity
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import dev.mokkery.verify.VerifyMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.client.data.discovery.DiscoveredServer as DataDiscoveredServer

/**
 * Regression coverage for the mDNS server-list growth bug: the same physical host advertising a
 * fresh mDNS `id` across restarts (or after a server DB reset) used to accumulate one permanent
 * Room row per id, so the list grew without bound and showed the same host repeatedly. The list
 * is now keyed by host:port — one row per physical server — and discovery results are no longer
 * blindly persisted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerDiscoveryDedupTest :
    FunSpec({
        fun entity(
            id: String,
            localUrl: String? = "http://192.168.86.36:8080",
            isActive: Boolean = false,
        ) = ServerEntity(
            id = id,
            name = "ListenUp",
            apiVersion = "v1",
            serverVersion = "0.0.1",
            localUrl = localUrl,
            remoteUrl = null,
            isActive = isActive,
            lastSeenAt = 0,
        )

        fun discovered(
            id: String,
            host: String = "192.168.86.36",
            port: Int = 8080,
        ) = DataDiscoveredServer(
            id = id,
            name = "ListenUp",
            host = host,
            port = port,
            apiVersion = "v1",
            serverVersion = "0.0.1",
        )

        test("collapses duplicate persisted rows for the same host into one entry") {
            runTest {
                val dao = mock<ServerDao>()
                val discovery = mock<ServerDiscoveryService>()
                everySuspend { dao.observeAll() } returns
                    flowOf(listOf(entity(id = "id-a"), entity(id = "id-b"), entity(id = "id-c")))
                every { discovery.discover() } returns flowOf(emptyList())

                val repository = ServerRepositoryImpl(dao, discovery, this)
                val servers = repository.observeServers().first()

                servers shouldHaveSize 1
                servers.first().server.localUrl shouldBe "http://192.168.86.36:8080"
            }
        }

        test("a persisted host is online when rediscovered under a different mDNS id") {
            runTest {
                val dao = mock<ServerDao>()
                val discovery = mock<ServerDiscoveryService>()
                everySuspend { dao.observeAll() } returns MutableStateFlow(listOf(entity(id = "old-id")))
                everySuspend { dao.getById(any()) } returns null
                everySuspend { dao.updateFromDiscovery(any(), any(), any(), any(), any(), any(), any()) } returns Unit
                everySuspend { dao.upsert(any()) } returns Unit
                every { discovery.discover() } returns MutableStateFlow(listOf(discovered(id = "fresh-id")))

                val repository = ServerRepositoryImpl(dao, discovery, this)
                advanceUntilIdle()
                val servers = repository.observeServers().drop(1).first()

                servers shouldHaveSize 1
                servers.first().isOnline shouldBe true
            }
        }

        test("discovered servers are not blindly persisted") {
            runTest {
                val dao = mock<ServerDao>()
                val discovery = mock<ServerDiscoveryService>()
                everySuspend { dao.observeAll() } returns MutableStateFlow(emptyList())
                everySuspend { dao.getById(any()) } returns null
                everySuspend { dao.upsert(any()) } returns Unit
                every { discovery.discover() } returns MutableStateFlow(listOf(discovered(id = "fresh-id")))

                val repository = ServerRepositoryImpl(dao, discovery, this)
                repository.observeServers().drop(1).first()
                advanceUntilIdle()

                verifySuspend(VerifyMode.exactly(0)) { dao.upsert(any()) }
            }
        }
    })
