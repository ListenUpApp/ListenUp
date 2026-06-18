package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import dev.mokkery.verify
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.client.data.discovery.DiscoveredServer as DataDiscoveredServer

/**
 * Unit tests for [ServerRepositoryImpl] — the live-discovery-to-picker-list mapper.
 *
 * The repository holds no persistence: it projects whatever mDNS currently advertises into
 * [com.calypsan.listenup.client.domain.model.ServerWithStatus] rows, deduped by the server's
 * stable mDNS id so a multi-homed server collapses to one entry (Bug 2).
 */
class ServerRepositoryTest :
    FunSpec({
        fun discovered(
            id: String,
            name: String = "ListenUp",
            host: String = "192.168.86.36",
            port: Int = 8080,
            remoteUrl: String? = null,
        ) = DataDiscoveredServer(
            id = id,
            name = name,
            host = host,
            port = port,
            apiVersion = "v1",
            serverVersion = "0.0.1",
            remoteUrl = remoteUrl,
        )

        test("observeServers emits an empty list when nothing is discovered") {
            runTest {
                val discovery = mock<ServerDiscoveryService>()
                every { discovery.discover() } returns flowOf(emptyList())

                val servers = ServerRepositoryImpl(discovery).observeServers().first()

                servers shouldHaveSize 0
            }
        }

        test("observeServers maps each discovered server to an online entry") {
            runTest {
                val discovery = mock<ServerDiscoveryService>()
                every { discovery.discover() } returns
                    flowOf(listOf(discovered(id = "srv-a", name = "Living Room")))

                val servers = ServerRepositoryImpl(discovery).observeServers().first()

                servers shouldHaveSize 1
                servers.first().server.id shouldBe "srv-a"
                servers.first().server.name shouldBe "Living Room"
                servers.first().server.localUrl shouldBe "http://192.168.86.36:8080"
                servers.first().isOnline.shouldBeTrue()
            }
        }

        test("observeServers carries every resolved address into the server's candidate URLs") {
            runTest {
                val discovery = mock<ServerDiscoveryService>()
                every { discovery.discover() } returns
                    flowOf(
                        listOf(
                            discovered(id = "srv-a", host = "192.168.86.39")
                                .copy(additionalHosts = listOf("192.168.86.37")),
                        ),
                    )

                val servers = ServerRepositoryImpl(discovery).observeServers().first()

                servers.first().server.localUrl shouldBe "http://192.168.86.39:8080"
                servers.first().server.localUrls shouldBe
                    listOf("http://192.168.86.39:8080", "http://192.168.86.37:8080")
            }
        }

        // Bug 2: a multi-homed server advertises the same stable mDNS id on more than one
        // address (e.g. IPv4 + IPv6, or wifi + ethernet). Those must collapse to ONE entry.
        test("observeServers collapses duplicate ids with different addresses into one entry") {
            runTest {
                val discovery = mock<ServerDiscoveryService>()
                every { discovery.discover() } returns
                    flowOf(
                        listOf(
                            discovered(id = "srv-a", host = "192.168.86.36", port = 8080),
                            discovered(id = "srv-a", host = "192.168.86.99", port = 9090),
                        ),
                    )

                val servers = ServerRepositoryImpl(discovery).observeServers().first()

                servers shouldHaveSize 1
                servers.first().server.id shouldBe "srv-a"
            }
        }

        test("observeServers keeps distinct ids as separate entries") {
            runTest {
                val discovery = mock<ServerDiscoveryService>()
                every { discovery.discover() } returns
                    flowOf(listOf(discovered(id = "srv-a"), discovered(id = "srv-b")))

                val servers = ServerRepositoryImpl(discovery).observeServers().first()

                servers shouldHaveSize 2
                servers.map { it.server.id }.toSet() shouldBe setOf("srv-a", "srv-b")
            }
        }

        test("startDiscovery delegates to the discovery service") {
            val discovery = mock<ServerDiscoveryService>()
            every { discovery.startDiscovery() } returns Unit

            ServerRepositoryImpl(discovery).startDiscovery()

            verify { discovery.startDiscovery() }
        }

        test("stopDiscovery delegates to the discovery service") {
            val discovery = mock<ServerDiscoveryService>()
            every { discovery.stopDiscovery() } returns Unit

            ServerRepositoryImpl(discovery).stopDiscovery()

            verify { discovery.stopDiscovery() }
        }
    })
