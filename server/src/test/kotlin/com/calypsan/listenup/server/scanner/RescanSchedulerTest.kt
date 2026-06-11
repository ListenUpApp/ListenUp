package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RescanSchedulerTest :
    FunSpec({

        test("rescans every registered library once per interval") {
            runTest {
                val calls = AtomicInteger(0)
                val scheduler =
                    RescanScheduler(
                        scope = backgroundScope,
                        interval = 1.hours,
                        libraryIds = { listOf(LibraryId("lib-a"), LibraryId("lib-b")) },
                        rescan = { calls.incrementAndGet() },
                    )
                val job = scheduler.start()
                testScheduler.advanceTimeBy(1.hours + 1.minutes)
                testScheduler.runCurrent()
                calls.get() shouldBe 2 // two libraries, one interval elapsed
                job!!.cancelAndJoin()
            }
        }

        test("a failing rescan does not kill the loop") {
            runTest {
                val calls = AtomicInteger(0)
                val scheduler =
                    RescanScheduler(
                        scope = backgroundScope,
                        interval = 1.hours,
                        libraryIds = { listOf(LibraryId("lib-a")) },
                        rescan = {
                            calls.incrementAndGet()
                            error("boom")
                        },
                    )
                val job = scheduler.start()
                testScheduler.advanceTimeBy(2.hours + 1.minutes)
                testScheduler.runCurrent()
                calls.get() shouldBe 2 // survived the first failure, fired again next interval
                job!!.cancelAndJoin()
            }
        }

        test("a non-positive interval disables the loop") {
            runTest {
                val scheduler =
                    RescanScheduler(
                        scope = backgroundScope,
                        interval = kotlin.time.Duration.ZERO,
                        libraryIds = { listOf(LibraryId("lib-a")) },
                        rescan = { error("should never run") },
                    )
                scheduler.start() shouldBe null
            }
        }
    })
