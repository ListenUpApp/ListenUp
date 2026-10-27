package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.design.theme.AA_NON_TEXT
import com.calypsan.listenup.client.design.theme.AA_TEXT
import com.calypsan.listenup.client.design.theme.DarkColorScheme
import com.calypsan.listenup.client.design.theme.LightColorScheme
import com.calypsan.listenup.client.design.theme.contrastRatio
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

/**
 * Now Playing on Android has no cover wash (F6): its ground is `surface`, and the card and menu sit on the surface
 * containers. Every ink the screen sets as text on them clears WCAG AA in both hand-tuned schemes: the status, the
 * narrators and the times (`onSurfaceVariant`), Close book (`error`),
 * and the error card's words.
 *
 * The sheets (PR 4) add their own pairs: the sleep gold (the armed control, the fade card and Keep listening), the coral
 * current chapter row, Cast while casting, the sheets' footnotes on `surfaceContainerLow`, and the gold meter against
 * its track, which is a non-text mark (WCAG 1.4.11, 3:1).
 *
 * The tablet's chapter panel (PR 5) adds the panel's ink on `surfaceContainer`, Back to current chapter's tonal pair,
 * and the current row's chapter bar: its fill against its track and against the coral row, both non-text (3:1).
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

            test("$name: Close book's red and the error card's words read at 4.5:1") {
                withClue("error on surfaceContainer") {
                    contrastRatio(scheme.error, scheme.surfaceContainer) shouldBeGreaterThanOrEqual AA_TEXT
                }
                withClue("onErrorContainer on errorContainer") {
                    contrastRatio(scheme.onErrorContainer, scheme.errorContainer) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }

            test("$name: the sheets' inks read at 4.5:1 on their containers") {
                listOf(
                    "onTertiaryContainer on tertiaryContainer (armed sleep, the fade card)" to
                        (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "onTertiary on tertiary (Keep listening)" to (scheme.onTertiary to scheme.tertiary),
                    "onPrimary on primary (the current chapter row)" to (scheme.onPrimary to scheme.primary),
                    "onPrimaryContainer on primaryContainer (Cast while casting)" to
                        (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "onSurfaceVariant on surfaceContainerLow (the sheets' footnotes)" to
                        (scheme.onSurfaceVariant to scheme.surfaceContainerLow),
                ).forEach { (pair, colors) ->
                    withClue(pair) { contrastRatio(colors.first, colors.second) shouldBeGreaterThanOrEqual AA_TEXT }
                }
            }

            test("$name: the tablet's chapter panel reads at 4.5:1 on surfaceContainer, and its button on its tone") {
                listOf(
                    "onSurface on surfaceContainer (the panel's titles and heading)" to
                        (scheme.onSurface to scheme.surfaceContainer),
                    "onSurfaceVariant on surfaceContainer (the panel's summary, numbers and durations)" to
                        (scheme.onSurfaceVariant to scheme.surfaceContainer),
                    "onSecondaryContainer on secondaryContainer (Back to current chapter)" to
                        (scheme.onSecondaryContainer to scheme.secondaryContainer),
                ).forEach { (pair, colors) ->
                    withClue(pair) { contrastRatio(colors.first, colors.second) shouldBeGreaterThanOrEqual AA_TEXT }
                }
            }

            test("$name: the current row's chapter bar reads at 3:1 against its track and against the row") {
                withClue("onPrimary (the fill) on onPrimaryContainer (the track)") {
                    contrastRatio(scheme.onPrimary, scheme.onPrimaryContainer) shouldBeGreaterThanOrEqual AA_NON_TEXT
                }
                withClue("onPrimary (the fill) on primary (the row)") {
                    contrastRatio(scheme.onPrimary, scheme.primary) shouldBeGreaterThanOrEqual AA_NON_TEXT
                }
            }

            test("$name: the sleep meter's gold reads at 3:1 against its track") {
                withClue("tertiary on surfaceContainerHighest") {
                    contrastRatio(scheme.tertiary, scheme.surfaceContainerHighest) shouldBeGreaterThanOrEqual
                        AA_NON_TEXT
                }
            }
        }
    })
