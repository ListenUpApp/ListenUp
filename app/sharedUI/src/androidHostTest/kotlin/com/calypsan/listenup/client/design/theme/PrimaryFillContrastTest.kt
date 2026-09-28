package com.calypsan.listenup.client.design.theme

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

/**
 * Pins the primary fill of both hand-tuned schemes to WCAG AA: a filled button, FAB or selected
 * indicator carries its `onPrimary` label at 4.5:1. The brand coral stays [ListenUpOrange]; the
 * light scheme's fill is a deeper coral at the same hue so white text on it passes.
 */
class PrimaryFillContrastTest :
    FunSpec({
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            test("$name: onPrimary on primary clears AA for text") {
                withClue("onPrimary on primary") {
                    contrastRatio(scheme.onPrimary, scheme.primary) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }
        }
    })
