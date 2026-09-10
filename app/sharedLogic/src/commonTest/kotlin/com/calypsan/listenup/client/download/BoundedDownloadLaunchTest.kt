package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The concurrency cap the iOS download service depends on. Before it, tapping "Download" on a
 * 40-file audiobook put 40 HTTP requests in flight at once against a single self-hosted server.
 */
class BoundedDownloadLaunchTest :
    FunSpec({

        test("at most `permits` actions run at the same time") {
            runTest {
                val permits = Semaphore(2)
                val gate = CompletableDeferred<Unit>()
                var inFlight = 0
                var peakInFlight = 0

                val jobs =
                    launchEachBounded((1..10).toList(), permits) {
                        inFlight++
                        if (inFlight > peakInFlight) peakInFlight = inFlight
                        gate.await()
                        inFlight--
                    }

                runCurrent()
                peakInFlight shouldBe 2

                gate.complete(Unit)
                jobs.joinAll()
                peakInFlight shouldBe 2
                inFlight shouldBe 0
            }
        }

        test("every item eventually runs") {
            runTest {
                val seen = mutableListOf<Int>()
                launchEachBounded((1..7).toList(), Semaphore(3)) { seen += it }.joinAll()
                seen.sorted() shouldBe (1..7).toList()
            }
        }
    })
