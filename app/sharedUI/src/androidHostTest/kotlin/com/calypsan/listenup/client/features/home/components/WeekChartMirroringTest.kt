package com.calypsan.listenup.client.features.home.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * The week chart is a time axis, and a time axis mirrors with the layout (M3, Android): left to right ending at today
 * in English, right to left in Arabic or Hebrew, so today stands at the reader's end of the row.
 */
class WeekChartMirroringTest :
    FunSpec({
        // Seven 40 px bars with 10 px gaps fill a 340 px chart exactly.
        val barWidth = 40f
        val gap = 10f
        val width = 340f

        test("left to right, the oldest day is at the left and today at the right") {
            weekChartBarLeft(index = 0, barWidth = barWidth, barSpacing = gap, chartWidth = width, isRtl = false) shouldBe 0f
            weekChartBarLeft(index = 6, barWidth = barWidth, barSpacing = gap, chartWidth = width, isRtl = false) shouldBe 300f
        }

        test("right to left, the oldest day is at the right and today at the left") {
            weekChartBarLeft(index = 0, barWidth = barWidth, barSpacing = gap, chartWidth = width, isRtl = true) shouldBe 300f
            weekChartBarLeft(index = 6, barWidth = barWidth, barSpacing = gap, chartWidth = width, isRtl = true) shouldBe 0f
        }

        test("right to left, each label stays centred under its own bar") {
            val spans =
                weekChartLabelSpans(
                    labelWidths = List(7) { 20f },
                    chartWidth = width,
                    barWidth = barWidth,
                    barSpacing = gap,
                    isRtl = true,
                )
            spans.first().start shouldBe (310f plusOrMinus 1e-3f)
            spans.last().start shouldBe (10f plusOrMinus 1e-3f)
        }

        test("right to left, a wide Today still stays inside the chart") {
            val spans =
                weekChartLabelSpans(
                    labelWidths = List(6) { 20f } + 60f,
                    chartWidth = width,
                    barWidth = barWidth,
                    barSpacing = gap,
                    isRtl = true,
                )
            spans.last().start shouldBe 0f
        }
    })
