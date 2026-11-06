package com.calypsan.listenup.client.presentation.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Position saves for an unrelated book must not rebuild the next-in-series walk's queries. */
class HomeSeriesWalkCostTest :
    FunSpec({
        test("ten position saves for an unrelated book do not rebuild the walk") {
            runTest {
                val rig = HomeSlotRig()
                repeat(20) { rig.finish("f$it", hoursAgo = 100L + it) }
                backgroundScope.launch { rig.slots().collect { } }
                runCurrent()
                val afterSettling = rig.nextInSeriesCalls
                repeat(10) { n ->
                    rig.play("other", hoursAgo = 0, positionMs = (n + 2) * 1_000L)
                    runCurrent()
                }
                afterSettling shouldBe SERIES_CANDIDATE_WALK
                rig.nextInSeriesCalls shouldBeLessThanOrEqual SERIES_CANDIDATE_WALK
            }
        }
    })
