package com.calypsan.listenup.client.data.sync

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest

class PresenceRefreshSignalTest :
    FunSpec({

        test("a collector on signal receives an emission after ping") {
            runTest {
                val presenceRefreshSignal = PresenceRefreshSignal()

                presenceRefreshSignal.signal.test {
                    presenceRefreshSignal.ping()
                    awaitItem()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("each ping is delivered to a subscribed collector") {
            runTest {
                val presenceRefreshSignal = PresenceRefreshSignal()

                presenceRefreshSignal.signal.test {
                    presenceRefreshSignal.ping()
                    awaitItem()
                    presenceRefreshSignal.ping()
                    awaitItem()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
