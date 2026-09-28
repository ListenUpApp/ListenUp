package com.calypsan.listenup.client.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Pins the hero ink recipe to WCAG AA on every color-block container of both hand-tuned schemes:
 * muted text reads at 4.5:1, full ink reads at 4.5:1 on a wash, and outlines reach 3:1.
 */
class HeroInkContrastTest :
    FunSpec({
        val schemes = listOf("light" to LightColorScheme, "dark" to DarkColorScheme)
        val containers: List<Pair<String, (ColorScheme) -> Pair<Color, Color>>> =
            listOf(
                "primary" to { s -> s.onPrimaryContainer to s.primaryContainer },
                "secondary" to { s -> s.onSecondaryContainer to s.secondaryContainer },
                "tertiary" to { s -> s.onTertiaryContainer to s.tertiaryContainer },
            )

        schemes.forEach { (schemeName, scheme) ->
            containers.forEach { (containerName, pick) ->
                val (ink, container) = pick(scheme)

                test("$schemeName $containerName: muted ink clears AA for text") {
                    val muted = HeroInk.muted(ink, container)
                    withClue("muted@${muted.alpha}") {
                        contrastRatio(muted, container) shouldBeGreaterThanOrEqual AA_TEXT
                    }
                }

                test("$schemeName $containerName: full ink on a wash clears AA for text") {
                    val wash = HeroInk.wash(ink, container)
                    withClue("wash@${wash.alpha}") {
                        contrastRatio(ink, wash.compositeOver(container)) shouldBeGreaterThanOrEqual AA_TEXT
                    }
                }

                test("$schemeName $containerName: muted ink on a wash clears AA for text") {
                    val washed = HeroInk.wash(ink, container).compositeOver(container)
                    val muted = HeroInk.muted(ink, washed)
                    withClue("muted@${muted.alpha} on wash") {
                        contrastRatio(muted, washed) shouldBeGreaterThanOrEqual AA_TEXT
                    }
                }

                test("$schemeName $containerName: an outline clears AA for non-text marks") {
                    val outline = HeroInk.outline(ink, container)
                    withClue("outline@${outline.alpha}") {
                        contrastRatio(outline, container) shouldBeGreaterThanOrEqual AA_NON_TEXT
                    }
                }
            }
        }

        schemes.forEach { (schemeName, scheme) ->
            test("$schemeName primary hero keeps the preferred quiet alphas") {
                val ink = scheme.onPrimaryContainer
                val container = scheme.primaryContainer
                // Colour alpha is stored in 8 bits, so compare to within one step.
                HeroInk.muted(ink, container).alpha shouldBe (HeroInk.MUTED_ALPHA plusOrMinus ALPHA_PRECISION)
                HeroInk.wash(ink, container).alpha shouldBe (HeroInk.WASH_ALPHA plusOrMinus ALPHA_PRECISION)
                HeroInk.outline(ink, container).alpha shouldBe (HeroInk.OUTLINE_ALPHA plusOrMinus ALPHA_PRECISION)
            }
        }

        test("dark primary hero gives full ink real headroom over AA") {
            // The banked-embers hero: deep enough that the quiet tiers never have to climb.
            contrastRatio(DarkColorScheme.onPrimaryContainer, DarkColorScheme.primaryContainer) shouldBeGreaterThanOrEqual
                HERO_INK_HEADROOM
        }
    })

private const val ALPHA_PRECISION = 0.005f
private const val HERO_INK_HEADROOM = 8.0
