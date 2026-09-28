package com.calypsan.listenup.client.features.home.components

import androidx.compose.ui.geometry.Size
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A day with no listening reads as empty: a small stub on the baseline, never a bar-wide circle,
 * however wide the chart's columns grow on a tablet.
 */
class EmptyDayStubTest :
    FunSpec({
        test("a wide column still draws only the small stub") {
            emptyDayStubSize(barWidth = 80f, stubMaxWidth = 16f, stubHeight = 6f) shouldBe Size(16f, 6f)
        }

        test("a column narrower than the stub narrows the stub with it") {
            emptyDayStubSize(barWidth = 12f, stubMaxWidth = 16f, stubHeight = 6f) shouldBe Size(12f, 6f)
        }
    })
