package com.calypsan.listenup.server.hardcover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

private const val USER = "u1"

/** "Sync now" stays honest: it ends only when a pull that began after it ends, and says how it ended. */
class HardcoverSyncActivityTest :
    FunSpec({

        test("a request is syncing until a pull that began after it catches up") {
            val activity = HardcoverSyncActivity()
            activity.syncRequested(USER)
            activity.isSyncing(USER) shouldBe true
            activity.pullCaughtUp(USER, served = activity.generation(USER))
            activity.isSyncing(USER) shouldBe false
            activity.syncNowFailed(USER) shouldBe false
        }

        test("a pull already running when the request came doesn't end it") {
            val activity = HardcoverSyncActivity()
            val inFlight = activity.generation(USER)
            activity.syncRequested(USER)
            activity.pullCaughtUp(USER, served = inFlight)
            activity.isSyncing(USER) shouldBe true
            activity.pullFailed(USER, served = inFlight)
            activity.syncNowFailed(USER) shouldBe false
        }

        test("a requested pull that fails ends the sync as failed; the next pull that catches up clears it") {
            val activity = HardcoverSyncActivity()
            activity.syncRequested(USER)
            activity.pullFailed(USER, served = activity.generation(USER))
            activity.isSyncing(USER) shouldBe false
            activity.syncNowFailed(USER) shouldBe true
            activity.pullCaughtUp(USER, served = activity.generation(USER))
            activity.syncNowFailed(USER) shouldBe false
        }

        test("asking again clears the last failure") {
            val activity = HardcoverSyncActivity()
            activity.syncRequested(USER)
            activity.pullFailed(USER, served = activity.generation(USER))
            activity.syncRequested(USER)
            activity.syncNowFailed(USER) shouldBe false
            activity.isSyncing(USER) shouldBe true
        }

        test("forgetting a user drops both flags") {
            val activity = HardcoverSyncActivity()
            activity.syncRequested(USER)
            activity.forget(USER)
            activity.isSyncing(USER) shouldBe false
            activity.syncNowFailed(USER) shouldBe false
        }

        test("every change names the user, so the linker can republish") {
            runTest {
                val activity = HardcoverSyncActivity()
                val seen = mutableListOf<String>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { activity.changes.collect { seen += it } }
                activity.syncRequested(USER)
                activity.healthChanged("u2")
                activity.pullCaughtUp(USER, served = activity.generation(USER))
                seen shouldBe listOf(USER, "u2", USER)
            }
        }
    })
