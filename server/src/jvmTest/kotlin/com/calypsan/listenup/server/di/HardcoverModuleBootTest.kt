package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.hardcover.HardcoverTokenProvider
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import org.koin.ktor.ext.get as koinGet

/**
 * Boots the real `Application.module()` with [hardcoverModule] in the graph, with and without a
 * Hardcover client id, and drives [HardcoverService] over the authed RPC mount. Proves the Koin
 * graph resolves every Hardcover binding (the token provider included, which no RPC path uses yet)
 * and that the service is registered, guarded and scoped to the caller.
 */
class HardcoverModuleBootTest :
    FunSpec({

        suspend fun ApplicationTestBuilder.rootToken(): String =
            publicAuthService()
                .setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
                .shouldSucceed()
                .accessToken
                .value

        test("with no client id the server boots, and connecting answers NotConfigured") {
            testApplication {
                useIsolatedTestConfig()
                application {
                    module()
                    koinGet<HardcoverTokenProvider>().shouldNotBeNull()
                }

                val service = authedService<HardcoverService>(rootToken())

                service.startLink().shouldFailWith<HardcoverError.NotConfigured>()
                service.observeConnection().first() shouldBe RpcEvent.Data(HardcoverConnection.NotConnected())
                service.disconnect().shouldSucceed()
            }
        }

        test("with a client id the server boots, and the caller's connection is watchable and endable") {
            testApplication {
                useIsolatedTestConfig(hardcoverClientId = "listenup-test-client")
                application {
                    module()
                    koinGet<HardcoverTokenProvider>().shouldNotBeNull()
                }

                val service = authedService<HardcoverService>(rootToken())

                service.observeConnection().first() shouldBe RpcEvent.Data(HardcoverConnection.NotConnected())
                service.disconnect().shouldSucceed()
            }
        }
    })
