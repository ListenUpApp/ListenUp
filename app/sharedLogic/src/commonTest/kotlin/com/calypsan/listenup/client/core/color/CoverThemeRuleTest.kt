package com.calypsan.listenup.client.core.color

import com.calypsan.listenup.client.domain.model.CoverTheme
import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemeTokens
import com.materialkolor.hct.Hct
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The cover colour rule against the three Covers boards (golden cases) and its guards. Board hexes are the designer's
 * approximations (decision D1), so a board is matched within ΔE 6 and the rule's own properties are asserted exactly.
 */
class CoverThemeRuleTest :
    FunSpec({
        fun themes(
            parts: Array<Pair<String, Int>>,
            style: CoverThemeStyle,
        ) = coverThemesOf(swatchesOf(*parts), style)

        fun hct(argb: Int) = Hct.fromInt(argb)

        test("The Way of Kings, iOS light: a cool silver wash, a gold track, the coral turned toward the cover") {
            val light = themes(WAY_OF_KINGS, IOS_STYLE).shouldNotBeNull().light
            val source = hct(hex("#AEB5C2"))

            hct(light.washArgb).tone shouldBe (91.0 plusOrMinus 0.5)
            hct(light.washArgb).chroma shouldBeLessThanOrEqual 6.5
            hueDistance(hct(light.washArgb).hue, source.hue) shouldBeLessThanOrEqual 10.0
            withClue("board #E4E5EB") { deltaE(light.washArgb, hex("#E4E5EB")) shouldBeLessThanOrEqual 6.0 }

            hueDistance(hct(light.trackArgb).hue, hct(hex("#E2A159")).hue) shouldBeLessThanOrEqual 5.0
            hct(light.trackArgb).tone shouldBe (85.0 plusOrMinus 0.5)

            val brand = hct(hex("#D73812"))
            hct(light.actionArgb).tone shouldBe (brand.tone plusOrMinus 0.5)
            hueDistance(hct(light.actionArgb).hue, brand.hue) shouldBe (15.0 plusOrMinus 1.0)
            light.onActionArgb shouldBe hex("#FFFFFF")
        }

        test("The Way of Kings, web light: the wash is mixed into the paper") {
            val light = themes(WAY_OF_KINGS, WEB_STYLE).shouldNotBeNull().light
            hct(light.washArgb).tone shouldBe (91.0 plusOrMinus 0.5)
            withClue("board #E6E4E6") { deltaE(light.washArgb, hex("#E6E4E6")) shouldBeLessThanOrEqual 6.0 }
        }

        test("the green cover: a sage wash, a green track (no second hue family), the coral turned toward green") {
            val ios = themes(GREEN, IOS_STYLE).shouldNotBeNull().light
            withClue("board #E3E9E4") { deltaE(ios.washArgb, hex("#E3E9E4")) shouldBeLessThanOrEqual 6.0 }
            withClue("board #D5E0D7") { deltaE(ios.trackArgb, hex("#D5E0D7")) shouldBeLessThanOrEqual 6.0 }
            val web = themes(GREEN, WEB_STYLE).shouldNotBeNull().light
            withClue("board #E4E8E2") { deltaE(web.washArgb, hex("#E4E8E2")) shouldBeLessThanOrEqual 6.0 }
        }

        test("a near-coral cover is neutral: the cover carries the warmth, the coral is unchanged") {
            themes(NEAR_CORAL, IOS_STYLE) shouldBe null
            themes(NEAR_CORAL, WEB_STYLE) shouldBe null
        }

        test("a greyscale cover is neutral") { themes(GREYSCALE, IOS_STYLE) shouldBe null }

        test("a near-black cover is neutral") { themes(NEAR_BLACK, IOS_STYLE) shouldBe null }

        test("a one-pixel cover still gets a wash") {
            val pixels = intArrayOf(hex("#AEB5C2"))
            coverThemesOf(requireNotNull(extractSwatches(pixels, 1, 1)), IOS_STYLE).shouldNotBeNull()
        }

        test("dark follows §2.3's written rule: wash tone 20, track tone 30, the dark coral harmonised") {
            val dark = themes(WAY_OF_KINGS, IOS_STYLE).shouldNotBeNull().dark
            hct(dark.washArgb).tone shouldBe (20.0 plusOrMinus 0.5)
            hct(dark.washArgb).chroma shouldBeLessThanOrEqual 8.5
            hct(dark.trackArgb).tone shouldBe (30.0 plusOrMinus 0.5)
            hct(dark.actionArgb).tone shouldBe (hct(hex("#FF6A3D")).tone plusOrMinus 0.5)
            dark.onActionArgb shouldBe hex("#1C0A04")
        }

        test("every pair a reader sees clears 4.5:1, on both platforms, in both appearances") {
            listOf(WAY_OF_KINGS, GREEN).forEach { cover ->
                mapOf("iOS" to IOS_STYLE, "web" to WEB_STYLE).forEach { (platform, style) ->
                    val themes = themes(cover, style).shouldNotBeNull()
                    listOf("light" to (themes.light to style.light), "dark" to (themes.dark to style.dark))
                        .forEach { (appearance, pair) ->
                            val (theme: CoverTheme, tokens: CoverThemeTokens) = pair
                            withClue("$platform $appearance ink on wash") {
                                contrastRatio(tokens.inkArgb, theme.washArgb) shouldBeGreaterThanOrEqual 4.5
                            }
                            withClue("$platform $appearance label on action") {
                                contrastRatio(theme.onActionArgb, theme.actionArgb) shouldBeGreaterThanOrEqual 4.5
                            }
                        }
                }
            }
        }

        test("the action guard trips: a coral too light for its label stays the unharmonised token") {
            val weak =
                CoverThemeTokens(brandArgb = hex("#FF8A65"), onBrandArgb = hex("#FFFFFF"), inkArgb = hex("#000000"), paperArgb = null)
            val theme = themes(WAY_OF_KINGS, CoverThemeStyle(weak, IOS_STYLE.dark)).shouldNotBeNull().light
            theme.actionArgb shouldBe hex("#FF8A65")
        }

        test("the wash guard trips: an ink that can't be read on the wash makes the cover neutral") {
            val pale =
                CoverThemeTokens(brandArgb = hex("#D73812"), onBrandArgb = hex("#FFFFFF"), inkArgb = hex("#CCCCCC"), paperArgb = null)
            themes(WAY_OF_KINGS, CoverThemeStyle(pale, IOS_STYLE.dark)) shouldBe null
        }

        test("hue distance is the short way round") {
            hueDistance(350.0, 10.0) shouldBe (20.0 plusOrMinus 1e-9)
            hueDistance(10.0, 190.0) shouldBe (180.0 plusOrMinus 1e-9)
        }
    })
