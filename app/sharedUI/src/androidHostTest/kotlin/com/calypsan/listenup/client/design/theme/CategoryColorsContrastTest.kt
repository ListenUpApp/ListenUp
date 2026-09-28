package com.calypsan.listenup.client.design.theme

import com.calypsan.listenup.client.features.settings.LICENSE_FALLBACK_COLOR
import com.calypsan.listenup.client.features.settings.LICENSE_FAMILY_COLORS
import com.calypsan.listenup.client.features.settings.deviceVisualFor
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

class CategoryColorsContrastTest :
    FunSpec({
        test("every palette tone clears AA for text, in light and in dark") {
            CategoryPalette.all.forEachIndexed { index, color ->
                withClue("palette[$index] light: content on container") {
                    contrastRatio(color.light.content, color.light.container) shouldBeGreaterThanOrEqual AA_TEXT
                }
                withClue("palette[$index] dark: content on container") {
                    contrastRatio(color.dark.content, color.dark.container) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }
        }

        test("every device type and licence family draws from the checked palette") {
            val deviceColors =
                listOf("phone", "tablet", "desktop", "laptop", "cast", "speaker", null)
                    .map { deviceVisualFor(it).color }
            val licenceColors = LICENSE_FAMILY_COLORS.values + LICENSE_FALLBACK_COLOR

            CategoryPalette.all shouldContainAll deviceColors + licenceColors
        }
    })
