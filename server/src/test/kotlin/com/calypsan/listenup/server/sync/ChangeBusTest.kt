@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ChangeBusTest :
    FunSpec({

        test("publish then subscribe receives the event") {
            runTest {
                val bus = ChangeBus()
                val syncEvent = SyncEvent.Created(id = "a", revision = 1, occurredAt = 100, payload = "x")
                val busEvent = BusEvent(domainName = "tags", event = syncEvent)
                val deferred = async { bus.subscribe().first() }
                // Yield so the subscriber coroutine can register on the SharedFlow before publish.
                advanceUntilIdle()
                bus.publish(busEvent)
                deferred.await() shouldBe busEvent
            }
        }

        test("multiple subscribers each receive every event") {
            runTest {
                val bus = ChangeBus()
                val ev1 = BusEvent("tags", SyncEvent.Created(id = "a", revision = 1, occurredAt = 100, payload = "x"))
                val ev2 = BusEvent("tags", SyncEvent.Updated<String>(id = "a", revision = 2, occurredAt = 101, payload = "y"))
                val sub1 = async { bus.subscribe().take(2).toList() }
                val sub2 = async { bus.subscribe().take(2).toList() }
                // Yield so both subscribers register before events are published.
                advanceUntilIdle()
                bus.publish(ev1)
                bus.publish(ev2)
                sub1.await() shouldContainExactly listOf(ev1, ev2)
                sub2.await() shouldContainExactly listOf(ev1, ev2)
            }
        }

        test("oldestRetainedRevision tracks the lowest in-buffer event") {
            runTest {
                val bus = ChangeBus()
                bus.oldestRetainedRevision() shouldBe null
                bus.publish(BusEvent("tags", SyncEvent.Created(id = "a", revision = 5, occurredAt = 100, payload = "x")))
                bus.publish(BusEvent("tags", SyncEvent.Created(id = "b", revision = 7, occurredAt = 101, payload = "y")))
                bus.oldestRetainedRevision()!! shouldBeGreaterThanOrEqual 5L
            }
        }
    })
