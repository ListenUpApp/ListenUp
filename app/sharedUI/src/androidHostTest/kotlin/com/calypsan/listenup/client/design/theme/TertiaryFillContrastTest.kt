package com.calypsan.listenup.client.design.theme

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

/**
 * Pins the amber roles of both hand-tuned schemes to WCAG AA: Book Detail's held triage panel sits on
 * `tertiaryContainer` and its Release to members button fills with `tertiary` (spec §3.2.3 Held), so each label clears
 * 4.5:1 on its fill.
 */
class TertiaryFillContrastTest :
    FunSpec({
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            test("$name: onTertiary on tertiary clears AA for text") {
                withClue("onTertiary on tertiary") {
                    contrastRatio(scheme.onTertiary, scheme.tertiary) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }
            test("$name: onTertiaryContainer on tertiaryContainer clears AA for text") {
                withClue("onTertiaryContainer on tertiaryContainer") {
                    contrastRatio(scheme.onTertiaryContainer, scheme.tertiaryContainer) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }
        }
    })
