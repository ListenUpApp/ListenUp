package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WCAG 2.2 AA for the palette, measured in both themes.
 *
 * The audit found muted ink at 2.7:1, white on coral at 3.5:1 and coral text at 3.1:1 — each a
 * token value, so each failed on every screen at once. This reads the tokens as the browser
 * resolves them (in a frame with the real sheet, light and then `data-theme="dark"`), composites
 * any alpha over the surface it sits on, and fails on the first pair below the line. A token
 * nudged back toward "prettier" fails here before it reaches a reader.
 */
class ContrastTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun palette(theme: Theme): Palette =
            Palette(
                frames.mount(width = 480, height = 400) {
                    Div(attrs = { classes("luw") }) {
                        Div(attrs = { classes("rs") }) {
                            Span(attrs = { classes("rs-s", "is-empty") }) { Text("★") }
                        }
                        Button(kind = ButtonKind.Primary) { Text("Sign in") }
                        Span(attrs = { classes("mdx-from") }) { Text("from Hardcover") }
                        Div(attrs = { id("sw-off") }) { SwitchField(label = "Sync", checked = false, onChange = {}) }
                        Div(attrs = { id("sw-on") }) { SwitchField(label = "Sync", checked = true, onChange = {}) }
                    }
                },
                theme,
            )

        Theme.entries.forEach { theme ->
            test("$theme: every tier of ink reads at 4.5:1 on paper and on every surface") {
                val palette = palette(theme)

                listOf("--ink", "--ink-2", "--ink-3").forEach { ink ->
                    GROUNDS.forEach { ground -> palette.assertContrast(ink, ground, AA_TEXT) }
                }
            }

            test("$theme: the ink tiers stay in order: ink over ink-2 over ink-3") {
                val palette = palette(theme)

                GROUNDS.forEach { ground ->
                    val ink = palette.contrast("--ink", ground)
                    val ink2 = palette.contrast("--ink-2", ground)
                    val ink3 = palette.contrast("--ink-3", ground)
                    withClue("$ground: ink $ink > ink-2 $ink2 > ink-3 $ink3") {
                        (ink > ink2 && ink2 > ink3) shouldBe true
                    }
                }
            }

            test("$theme: coral as text reads at 4.5:1 on paper and on a card") {
                val palette = palette(theme)

                listOf("--paper", "--surface").forEach { palette.assertContrast("--coral-text", it, AA_TEXT) }
            }

            test("$theme: the label on a coral fill reads at 4.5:1") {
                palette(theme).assertContrast("--on-coral", "--coral-fill", AA_TEXT)
            }

            test("$theme: danger reads at 4.5:1 as text, and carries its label as a fill") {
                val palette = palette(theme)

                GROUNDS.forEach { palette.assertContrast("--danger", it, AA_TEXT) }
                palette.assertContrast("--danger", "--danger-soft", AA_TEXT)
                palette.assertContrast("--on-danger", "--danger-fill", AA_TEXT)
            }

            test("$theme: a warning reads at 4.5:1 on its own wash") {
                palette(theme).assertContrast("--warn", "--warn-soft", AA_TEXT)
            }

            test("$theme: an empty rating star is visible at 3:1 against the card it sits on") {
                // WCAG 1.4.11: an empty star is the part of the control that says "you can
                // rate higher", and at 1.2:1 it was not there at all.
                val palette = palette(theme)
                val star = palette.frame.find(".rs-s.is-empty")

                listOf("--surface", "--surface-2").forEach { ground ->
                    palette.assertContrast(palette.elementColour(star, "color"), ground, AA_NON_TEXT, ".rs-s.is-empty")
                }
            }

            // #1562: the controls a low-vision reader has to find by their edges alone (WCAG 1.4.11).
            test("$theme: a control's outline marks its edge at 3:1 on paper and on every surface") {
                val palette = palette(theme)

                GROUNDS.forEach { palette.assertContrast("--outline", it, AA_NON_TEXT) }
            }

            test("$theme: an outlined button draws its edge in the outline, not the 1.2:1 divider") {
                val palette = palette(theme)
                val probe =
                    palette.frame.host.ownerDocument!!
                        .createElement("button")
                probe.className = "btn btn-secondary btn-md"
                palette.frame.find(".luw").appendChild(probe)

                palette.elementColour(probe, "border-top-color") shouldBe palette.token("--outline")
            }

            test("$theme: an off switch's track stands out from its card, and its thumb from the track") {
                val palette = palette(theme)
                val track = palette.frame.find("#sw-off .sw-track")
                val thumb = palette.frame.find("#sw-off .sw-thumb")

                val edge = palette.elementColour(track, "border-top-color").over(palette.token("--surface"))
                palette.assertContrast(edge, "--surface", AA_NON_TEXT, "off track edge")
                palette.assertContrast(palette.elementColour(thumb, "background-color"), "--surface-3", AA_NON_TEXT, "off thumb")
            }

            test("$theme: an on switch's thumb stands out from its coral track") {
                val palette = palette(theme)
                val track = palette.elementColour(palette.frame.find("#sw-on .sw-track"), "background-color")
                val thumb = palette.elementColour(palette.frame.find("#sw-on .sw-thumb"), "background-color")

                withClue("on thumb on track: ${contrastRatio(thumb, track)}") {
                    contrastRatio(thumb, track) shouldBeGreaterThanOrEqual AA_NON_TEXT
                }
            }

            test("$theme: a chosen segment's outline shows at 3:1 against the group around it") {
                val palette = palette(theme)
                val ring = palette.token("--outline").over(palette.token("--surface"))

                palette.assertContrast(ring, "--surface-2", AA_NON_TEXT, "segment ring")
            }

            test("$theme: success reads at 4.5:1 on its own wash and on the Hardcover hero's coral wash") {
                val palette = palette(theme)
                val heroWash = palette.token("--coral-soft").over(palette.token("--paper"))

                palette.assertContrast("--success", "--success-soft", AA_TEXT)
                withClue("success on the hero wash: ${contrastRatio(palette.token("--success"), heroWash)}") {
                    contrastRatio(palette.token("--success"), heroWash) shouldBeGreaterThanOrEqual AA_TEXT
                }
            }

            test("$theme: the 'from Hardcover' chip reads at 4.5:1 on its own fill") {
                val palette = palette(theme)
                val chip = palette.frame.find(".mdx-from")
                val fill = palette.elementColour(chip, "background-color").over(palette.token("--surface"))
                val ink = palette.elementColour(chip, "color").over(fill)

                withClue("chip: ${contrastRatio(ink, fill)}") { contrastRatio(ink, fill) shouldBeGreaterThanOrEqual AA_TEXT }
            }

            test("$theme: the primary button is filled with coral-fill, not the brand mark's coral") {
                val palette = palette(theme)
                val button = palette.frame.find(".btn-primary")

                palette.elementColour(button, "background-color") shouldBe palette.token("--coral-fill")
            }

            test("$theme: the page itself is paper, so nothing paints white before the app mounts") {
                val palette = palette(theme)
                val root =
                    palette.frame.host.ownerDocument!!
                        .documentElement!!

                palette.elementColour(root, "background-color") shouldBe palette.token("--paper")
            }
        }
    })

/** The two palettes the sheet carries. */
private enum class Theme { Light, Dark }

/** What text and controls are drawn on. */
private val GROUNDS = listOf("--paper", "--surface", "--surface-2")

private const val AA_TEXT = 4.5
private const val AA_NON_TEXT = 3.0

/** An sRGB colour with alpha, channels 0–255. */
private data class Rgba(
    val r: Double,
    val g: Double,
    val b: Double,
    val a: Double = 1.0,
) {
    /** This colour painted over an opaque [ground]. */
    fun over(ground: Rgba): Rgba =
        Rgba(
            r * a + ground.r * (1 - a),
            g * a + ground.g * (1 - a),
            b * a + ground.b * (1 - a),
        )

    /** WCAG relative luminance. */
    fun luminance(): Double {
        fun channel(value: Double): Double {
            val c = value / 255
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    override fun toString(): String = "rgba(${r.roundToInt()}, ${g.roundToInt()}, ${b.roundToInt()}, $a)"
}

private fun contrastRatio(
    a: Rgba,
    b: Rgba,
): Double {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** Chromium's computed form: `rgb(r, g, b)` or `rgba(r, g, b, a)`. */
private fun parseColour(computed: String): Rgba {
    val parts =
        Regex("""rgba?\(([^)]*)\)""")
            .find(computed)
            ?.groupValues
            ?.get(1)
            ?.split(",")
            ?.map { it.trim().toDouble() }
            ?: error("not an rgb() colour: '$computed'")
    return Rgba(parts[0], parts[1], parts[2], parts.getOrElse(3) { 1.0 })
}

/** One themed frame, and the colours its tokens resolve to there. */
private class Palette(
    val frame: ViewportFrame,
    theme: Theme,
) {
    init {
        val root = frame.host.ownerDocument!!.documentElement!!
        if (theme == Theme.Dark) root.setAttribute("data-theme", "dark") else root.removeAttribute("data-theme")
    }

    // Created in the runner's document and adopted on append, like the frame's own host, so the
    // cast holds: an element the frame's document made carries the frame's prototypes.
    private val probe: HTMLElement =
        (document.createElement("span") as HTMLElement).also { frame.host.appendChild(it) }

    /** [name] as the browser resolves it — failing, rather than inheriting, when it is undefined. */
    fun token(name: String): Rgba {
        val root = frame.host.ownerDocument!!.documentElement!!
        withClue("$name is defined") { frame.css(root, name).trim().shouldNotBeEmpty() }
        probe.style.setProperty("color", "var($name)")
        return parseColour(frame.css(probe, "color"))
    }

    fun elementColour(
        element: org.w3c.dom.Element,
        property: String,
    ): Rgba = parseColour(frame.css(element, property))

    fun contrast(
        foreground: String,
        ground: String,
    ): Double {
        val base = token(ground)
        return contrastRatio(token(foreground).over(base), base)
    }

    fun assertContrast(
        foreground: String,
        ground: String,
        floor: Double,
    ) = assertContrast(token(foreground), ground, floor, foreground)

    fun assertContrast(
        foreground: Rgba,
        ground: String,
        floor: Double,
        label: String,
    ) {
        val base = token(ground)
        val ratio = contrastRatio(foreground.over(base), base)
        withClue("$label on $ground: ${(ratio * 100).roundToInt() / 100.0}:1, needs $floor:1") {
            ratio shouldBeGreaterThanOrEqual floor
        }
    }
}
