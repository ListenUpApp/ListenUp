package com.calypsan.listenup.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VolumeBoostLimitsTest :
    FunSpec({
        test("boost range spans off to plus twelve decibels") {
            VolumeBoostLimits.MIN_DB shouldBe 0f
            VolumeBoostLimits.MAX_DB shouldBe 12f
            VolumeBoostLimits.RANGE shouldBe 0f..12f
        }

        test("the ladder is the whole range in three-decibel rungs") {
            // Written out rather than re-derived from MIN/MAX/STEP: a test that recomputes the
            // production expression passes whatever that expression happens to say, which is no
            // test at all. These are the five rungs every client is expected to show.
            VolumeBoostLimits.PRESETS_DB shouldBe listOf(0f, 3f, 6f, 9f, 12f)
        }

        test("every rung is a boost the range allows") {
            // The ladder and the clamp are read by different call sites — a rung outside the
            // range would be offered and then silently coerced away on selection.
            VolumeBoostLimits.PRESETS_DB.forEach { rung ->
                (rung in VolumeBoostLimits.RANGE) shouldBe true
            }
        }
    })
