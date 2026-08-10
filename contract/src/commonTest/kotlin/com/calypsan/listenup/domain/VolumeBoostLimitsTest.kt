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
    })
