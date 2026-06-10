package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.data.remote.InstanceRpcFactory
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.AppResult as RpcResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Drives [InstanceRepositoryImpl]'s RPC-backed verification path through a fake
 * [InstanceRpcFactory] — no network. Pins the two screen-one behaviours:
 *  - [InstanceRepositoryImpl.verifyServer] returns the [ServerInfo] + the URL
 *    that connected.
 *  - [InstanceRepositoryImpl.getServerInfo] bridges the contract result to the
 *    client `core.AppResult`, and fails (without calling the factory) when no
 *    server URL is configured.
 */
class InstanceRepositoryImplTest :
    FunSpec({

        val serverInfo =
            ServerInfo(
                name = "ListenUp",
                version = "0.0.1",
                apiVersion = "v1",
                setupRequired = true,
                registrationPolicy = RegistrationPolicy.OPEN,
                instanceId = "test-instance",
            )

        /** Fake factory: records the ws URL it was asked for, returns a canned result. */
        class FakeInstanceRpcFactory(
            private val result: RpcResult<ServerInfo>,
        ) : InstanceRpcFactory {
            var lastWsUrl: String? = null

            override suspend fun getServerInfo(wsBaseUrl: String): RpcResult<ServerInfo> {
                lastWsUrl = wsBaseUrl
                return result
            }
        }

        test("verifyServer returns the ServerInfo and the verified URL on success") {
            val factory = FakeInstanceRpcFactory(RpcResult.Success(serverInfo))
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { null },
                    instanceRpcFactory = factory,
                    persistRemoteUrl = { },
                )

            val result = repository.verifyServer("https://library.example.com")

            val verified = result.shouldBeInstanceOf<AppResult.Success<*>>()
            val data = verified.data as com.calypsan.listenup.client.domain.repository.VerifiedServer
            data.serverInfo shouldBe serverInfo
            data.verifiedUrl shouldBe "https://library.example.com"
            // The factory is connected over the ws-scheme equivalent.
            factory.lastWsUrl shouldBe "wss://library.example.com"
        }

        test("getServerInfo returns failure without touching the factory when no URL is configured") {
            val factory = FakeInstanceRpcFactory(RpcResult.Success(serverInfo))
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { null },
                    instanceRpcFactory = factory,
                    persistRemoteUrl = { },
                )

            val result = repository.getServerInfo()

            result.shouldBeInstanceOf<AppResult.Failure>()
            factory.lastWsUrl shouldBe null
        }

        test("getServerInfo bridges a configured-URL success to core.Success") {
            val factory = FakeInstanceRpcFactory(RpcResult.Success(serverInfo))
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { ServerUrl("http://192.168.1.10:8080") },
                    instanceRpcFactory = factory,
                    persistRemoteUrl = { },
                )

            val result = repository.getServerInfo()

            val success = result.shouldBeInstanceOf<AppResult.Success<ServerInfo>>()
            success.data shouldBe serverInfo
            factory.lastWsUrl shouldBe "ws://192.168.1.10:8080"
        }

        test("getServerInfo persists the remote URL from the fetched ServerInfo") {
            val infoWithRemote = serverInfo.copy(remoteUrl = "https://library.example.com")
            val factory = FakeInstanceRpcFactory(RpcResult.Success(infoWithRemote))
            var persisted: String? = "UNSET"
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { ServerUrl("http://192.168.1.10:8080") },
                    instanceRpcFactory = factory,
                    persistRemoteUrl = { url -> persisted = url },
                )

            repository.getServerInfo(forceRefresh = true)

            persisted shouldBe "https://library.example.com"
        }

        test("getServerInfo persists a null remote URL when the server has none") {
            val factory = FakeInstanceRpcFactory(RpcResult.Success(serverInfo.copy(remoteUrl = null)))
            var persisted: String? = "UNSET"
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { ServerUrl("http://192.168.1.10:8080") },
                    instanceRpcFactory = factory,
                    persistRemoteUrl = { url -> persisted = url },
                )

            repository.getServerInfo(forceRefresh = true)

            persisted shouldBe null
        }

        test("getServerInfo bridges a contract Failure to core.Failure") {
            val factory = FakeInstanceRpcFactory(RpcResult.Failure(InternalError()))
            val repository =
                InstanceRepositoryImpl(
                    getServerUrl = { ServerUrl("http://192.168.1.10:8080") },
                    instanceRpcFactory = factory,
                    persistRemoteUrl = { },
                )

            val result = repository.getServerInfo()

            result.shouldBeInstanceOf<AppResult.Failure>()
        }
    })
