package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.error.PushError
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.PushRpcFactory
import com.calypsan.listenup.client.data.remote.catchingRpcResult
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Fake [PushRpcFactory] that routes [callResult] through the REAL boundary
 * [catchingRpcResult], so repository tests exercise the same throw→Failure /
 * cancellation-rethrow semantics the production
 * [com.calypsan.listenup.client.data.remote.RpcProxyCache] engine provides —
 * without a live WebSocket. [provide] yields the service.
 */
private class FakePushRpcFactory(
    private val provide: suspend () -> PushService,
) : PushRpcFactory {
    override suspend fun get(): PushService = provide()

    override suspend fun <T> callResult(block: suspend (PushService) -> AppResult<T>): AppResult<T> =
        catchingRpcResult { block(provide()) }

    override suspend fun invalidate() {}
}

/**
 * Unit tests for [PushRepositoryImpl] — purely RPC-dispatched, no local mirror.
 */
class PushRepositoryImplTest :
    FunSpec({

        test("registerToken delegates to the authed PushService") {
            runTest {
                val service =
                    mock<PushService> {
                        everySuspend { registerToken("t", PushPlatform.ANDROID) } returns AppResult.Success(Unit)
                    }
                val repo = PushRepositoryImpl(FakePushRpcFactory { service }, PushPlatform.ANDROID)

                val result = repo.registerToken("t")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { service.registerToken("t", PushPlatform.ANDROID) }
            }
        }

        test("sendTestNotification passes failures through untouched") {
            runTest {
                val failure = AppResult.Failure(PushError.PushDisabled())
                val service =
                    mock<PushService> {
                        everySuspend { sendTestNotification() } returns failure
                    }
                val repo = PushRepositoryImpl(FakePushRpcFactory { service }, PushPlatform.ANDROID)

                val result = repo.sendTestNotification()

                result shouldBe failure
            }
        }
    })
