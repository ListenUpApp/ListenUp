@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncControl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Broadcast control mechanics: a content-free nudge published via
 * [ChangeBus.broadcastControl] must reach EVERY subscriber, regardless of the
 * userId they filter on. This backs the firehose's all-subscriber delivery of
 * `SyncControl.ActiveSessionsChanged` for social presence.
 */
class ChangeBusBroadcastTest :
    FunSpec({

        test("broadcastControl reaches every subscriber regardless of userId") {
            val bus = ChangeBus()
            runTest {
                val sub1 = async { bus.subscribeControl().first() }
                val sub2 = async { bus.subscribeControl().first() }
                advanceUntilIdle()

                bus.broadcastControl(SyncControl.ActiveSessionsChanged)

                val frame1 = sub1.await()
                val frame2 = sub2.await()

                frame1.control shouldBe SyncControl.ActiveSessionsChanged
                frame1.userId shouldBe ChangeBus.BROADCAST
                frame2.control shouldBe SyncControl.ActiveSessionsChanged
                frame2.userId shouldBe ChangeBus.BROADCAST
            }
        }
    })
