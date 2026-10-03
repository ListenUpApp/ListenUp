package com.calypsan.listenup.client.design.theme

import androidx.compose.ui.graphics.Color
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan

class ExtendedColorsContrastTest :
    FunSpec({
        test("the contrast helper matches the WCAG endpoints") {
            contrastRatio(Color.Black, Color.White) shouldBeGreaterThanOrEqual 20.99
            contrastRatio(Color.White, Color.White) shouldBeLessThan 1.01
        }

        listOf(
            Triple("light", LightExtendedColors, LightColorScheme),
            Triple("dark", DarkExtendedColors, DarkColorScheme),
        ).forEach { (name, extended, scheme) ->
            test("$name: text on the success fills clears AA") {
                withClue("onSuccess on success") {
                    contrastRatio(extended.onSuccess, extended.success) shouldBeGreaterThanOrEqual AA_TEXT
                }
                withClue("onSuccessContainer on successContainer") {
                    contrastRatio(extended.onSuccessContainer, extended.successContainer) shouldBeGreaterThanOrEqual
                        AA_TEXT
                }
            }

            test("$name: success text clears AA on every container a card sits on") {
                mapOf(
                    "surface" to scheme.surface,
                    "surfaceContainerLow" to scheme.surfaceContainerLow,
                    "surfaceContainer (Book Detail's Hardcover card)" to scheme.surfaceContainer,
                    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                ).forEach { (surfaceName, surface) ->
                    withClue("success text on $surfaceName") {
                        contrastRatio(extended.success, surface) shouldBeGreaterThanOrEqual AA_TEXT
                    }
                }
            }

            test("$name: the outline that edges outlined buttons is visible on every surface") {
                mapOf(
                    "surface" to scheme.surface,
                    "surfaceContainerLow" to scheme.surfaceContainerLow,
                    "surfaceContainer" to scheme.surfaceContainer,
                    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                    "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                ).forEach { (surfaceName, surface) ->
                    withClue("outline on $surfaceName") {
                        contrastRatio(scheme.outline, surface) shouldBeGreaterThanOrEqual AA_NON_TEXT
                    }
                }
            }

            test("$name: the success mark reads on every surface it sits on") {
                mapOf(
                    "surface" to scheme.surface,
                    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                    "primaryContainer (the Devices hero)" to scheme.primaryContainer,
                ).forEach { (surfaceName, surface) ->
                    withClue("success on $surfaceName") {
                        contrastRatio(extended.success, surface) shouldBeGreaterThanOrEqual AA_NON_TEXT
                    }
                }
            }
        }
    })
