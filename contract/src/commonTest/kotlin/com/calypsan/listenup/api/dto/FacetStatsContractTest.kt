package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class FacetStatsContractTest :
    FunSpec({
        test("FacetStats round-trips through contractJson") {
            val v = FacetStats(bookCount = 170, totalDurationMs = 7_704_000_000L)
            contractJson.decodeFromString<FacetStats>(contractJson.encodeToString(v)) shouldBe v
        }

        test("EMPTY is zero/zero") {
            FacetStats.EMPTY shouldBe FacetStats(0, 0L)
        }
    })
