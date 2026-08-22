package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.error.PushError
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [PushRepositoryImpl] — purely RPC-dispatched, no local mirror. The channel is
 * [RpcChannel.forTest], so calls exercise the real dispatch fold without a live WebSocket.
 */
class PushRepositoryImplTest :
    FunSpec({

        test("registerToken delegates to the authed PushService") {
            runTest {
                val service =
                    mock<PushService> {
                        everySuspend { registerToken("t", PushPlatform.ANDROID) } returns AppResult.Success(Unit)
                    }
                val repo =
                    PushRepositoryImpl(
                        RpcChannel.forTest(service),
                        RpcChannel.forTest(mock<AuthServicePublic>()),
                    )

                val result = repo.registerToken("t", PushPlatform.ANDROID)

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { service.registerToken("t", PushPlatform.ANDROID) }
            }
        }

        test("registerRegistrationWatchToken rides the public channel with this build's platform") {
            runTest {
                val publicService =
                    mock<AuthServicePublic> {
                        everySuspend {
                            registerRegistrationWatchToken("u1", "t", PushPlatform.ANDROID)
                        } returns AppResult.Success(Unit)
                    }
                val repo =
                    PushRepositoryImpl(
                        RpcChannel.forTest(mock<PushService>()),
                        RpcChannel.forTest(publicService),
                    )

                repo.registerRegistrationWatchToken("u1", "t", PushPlatform.ANDROID).shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { publicService.registerRegistrationWatchToken("u1", "t", PushPlatform.ANDROID) }
            }
        }

        test("sendTestNotification passes failures through untouched") {
            runTest {
                val failure = AppResult.Failure(PushError.PushDisabled())
                val service =
                    mock<PushService> {
                        everySuspend { sendTestNotification() } returns failure
                    }
                val repo =
                    PushRepositoryImpl(
                        RpcChannel.forTest(service),
                        RpcChannel.forTest(mock<AuthServicePublic>()),
                    )

                val result = repo.sendTestNotification()

                result shouldBe failure
            }
        }
    })
