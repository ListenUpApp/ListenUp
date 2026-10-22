package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.design.theme.AA_TEXT
import com.calypsan.listenup.client.design.theme.DarkColorScheme
import com.calypsan.listenup.client.design.theme.LightColorScheme
import com.calypsan.listenup.client.design.theme.contrastRatio
import com.calypsan.listenup.client.design.theme.primaryInk
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

/**
 * Now Playing on Android has no cover wash (F6): its ground is `surface`, and the card and menu sit on the surface
 * containers. Every ink the screen sets as text on them clears WCAG AA in both hand-tuned schemes: the status, the
 * narrators, the times and the book sentence (`onSurfaceVariant`), the counter and "Playing on …" (`primaryInk`: the
 * brand coral is 4.48:1 on the light surface, a hair under AA), Close book (`error`),
 * and the error card's words.
 */
class NowPlayingInkContrastTest :
    FunSpec({
        listOf("light" to LightColorScheme, "dark" to DarkColorScheme).forEach { (name, scheme) ->
            test("$name: the screen's secondary ink reads at 4.5:1 on the ground and on the finished card") {
                listOf(
                    "surface" to scheme.surface,
                    "surfaceContainer" to scheme.surfaceContainer,
                ).forEach { (ground, color) ->
                    withClue("onSurfaceVariant on $ground") {
                        contrastRatio(scheme.onSurfaceVariant, color) shouldBeGreaterThanOrEqual AA_TEXT
                    }
                }
            }

            test("$name: casting's coral, Close book's red and the error card's words read at 4.5:1") {
                withClue("primaryInk on surface") {
                    contrastRatio(scheme.primaryInk, scheme.surface) shouldBeGreaterThanOrEqual AA_TEXT
                }
                withClue("error on surfaceContainer") {
                    contrastRatio(scheme.error, scheme.surfaceContainer) shouldBeGreaterThanOrEqual AA_TEXT
                }
                withClue("onErrorContainer on errorContainer") {
                    contrastRatio(scheme.onErrorContainer, scheme.errorContainer) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }
        }
    })
