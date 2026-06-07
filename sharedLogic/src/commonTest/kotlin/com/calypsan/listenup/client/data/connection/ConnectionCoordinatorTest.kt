package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.core.ServerUrl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

class ConnectionCoordinatorTest :
    FunSpec({
        class FakeInvalidator : RpcCacheInvalidator {
            var count = 0

            override suspend fun invalidateAll() {
                count++
            }
        }

        test("invalidates when the active URL host changes") {
            val active = MutableStateFlow<ServerUrl?>(ServerUrl("http://192.168.1.10:8080"))
            val invalidator = FakeInvalidator()
            val scope = TestScope(StandardTestDispatcher())
            ConnectionCoordinator(active, { active.value }, invalidator, scope).start()
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 0

            active.value = ServerUrl("https://remote.example.com")
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 1
        }

        test("does not invalidate when host unchanged (trailing slash / same host:port)") {
            val active = MutableStateFlow<ServerUrl?>(ServerUrl("http://192.168.1.10:8080"))
            val invalidator = FakeInvalidator()
            val scope = TestScope(StandardTestDispatcher())
            ConnectionCoordinator(active, { active.value }, invalidator, scope).start()
            scope.testScheduler.advanceUntilIdle()

            active.value = ServerUrl("http://192.168.1.10:8080/")
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 0
        }

        test("ignores null emissions") {
            val active = MutableStateFlow<ServerUrl?>(null)
            val invalidator = FakeInvalidator()
            val scope = TestScope(StandardTestDispatcher())
            ConnectionCoordinator(active, { active.value }, invalidator, scope).start()
            scope.testScheduler.advanceUntilIdle()
            active.value = null
            scope.testScheduler.advanceUntilIdle()
            invalidator.count shouldBe 0
        }
    })
