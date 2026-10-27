package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.presentation.player.MorphElement
import com.calypsan.listenup.client.presentation.player.fade
import com.calypsan.listenup.client.presentation.player.reveal
import com.calypsan.listenup.client.presentation.player.sidesScale
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.attributes.InputType
import org.w3c.dom.css.CSSStyleSheet

/**
 * The canvas `Spec` table, as CSS computes it from one number. The windows are written once from the shared
 * [MorphElement] (`writeMorphTimeline`), and every frame writes only `--mp-p`: these specs set that one number and read
 * what the browser computes, element by element, so a window that drifts from Android's names itself.
 */
class PlayerMorphCssParityTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun mountFixture(): ViewportFrame {
            val frame =
                frames.mount(PHONE_WIDTH, PHONE_HEIGHT) {
                    Div(attrs = { classes("shell", "shell-morphing") }) {
                        Aside(attrs = { classes("sidebar") }) {}
                        Div(attrs = { classes("tport") }) {
                            Div(attrs = { classes("tport-line") })
                            A(href = "#", attrs = { classes("tport-body") }) {
                                Span(attrs = { classes("tport-cover") }) {}
                                Span(attrs = { classes("tport-text") }) {
                                    Span(attrs = { classes("tport-t") }) {}
                                    Span(attrs = { classes("tport-sub", "phone") }) {}
                                }
                            }
                            Button(attrs = { classes("tport-back") }) {}
                        }
                        Div(attrs = { classes("np-slot", "np-layer") }) {
                            Div(attrs = { classes("np-page", "np-morph") }) {
                                Div(attrs = { classes("page-h") }) {}
                                P(attrs = { classes("np-ch-title", "np-mob") }) {}
                                P(attrs = { classes("np-narrated", "np-mob") }) {}
                                Div(attrs = { classes("np-scrubber") }) {
                                    Span(attrs = { classes("np-time") }) {}
                                    Input(InputType.Range, attrs = { classes("np-scrub") })
                                }
                                Div(attrs = { classes("np-transport") }) {
                                    Button(attrs = { classes("np-step", "np-prev") }) {}
                                    Button(attrs = { classes("np-play") }) {}
                                }
                                Div(attrs = { classes("np-pills") }) {}
                            }
                        }
                    }
                }
            writeMorphTimeline(frame.find(".shell"))
            return frame
        }

        fun ViewportFrame.at(p: Double) {
            find(".shell").style.setProperty(MORPH_PROGRESS, p.toString())
        }

        fun ViewportFrame.opacity(selector: String): Double = css(find(selector), "opacity").toDouble()

        /** Arriving elements: the window read forwards. */
        val arriving =
            mapOf(
                MorphElement.Header to ".np-morph .page-h",
                MorphElement.Chapter to ".np-ch-title",
                MorphElement.Narrator to ".np-narrated",
                MorphElement.Times to ".np-time",
                MorphElement.TransportSides to ".np-prev",
                MorphElement.Settings to ".np-pills",
            )

        /** Leaving elements: the window read as how far they have gone. */
        val leaving =
            mapOf(
                MorphElement.BarSecondLine to ".tport-sub",
                MorphElement.BarSkipBack to ".tport-back",
            )

        arriving.forEach { (element, selector) ->
            test("$element ($selector) fades in on its window") {
                val frame = mountFixture()
                PROGRESSES.forEach { p ->
                    frame.at(p)
                    frame.opacity(selector) shouldBe (reveal(element, p.toFloat()).toDouble() plusOrMinus TOLERANCE)
                }
            }
        }

        leaving.forEach { (element, selector) ->
            test("$element ($selector) fades out on its window") {
                val frame = mountFixture()
                PROGRESSES.forEach { p ->
                    frame.at(p)
                    frame.opacity(selector) shouldBe (fade(element, p.toFloat()).toDouble() plusOrMinus TOLERANCE)
                }
            }
        }

        test("the sheet's colour goes from the bar's to the page's, linearly over 0 to 0.6") {
            val frame = mountFixture()
            val sheet = frame.find(".np-morph")
            PROGRESSES.forEach { p ->
                frame.at(p)
                val tint = frame.window.getComputedStyle(sheet, "::before").getPropertyValue("opacity").toDouble()
                tint shouldBe (fade(MorphElement.SheetColour, p.toFloat()).toDouble() plusOrMinus TOLERANCE)
            }
        }

        test("the tab bar slides down 84px over the first 40 %") {
            val frame = mountFixture()
            PROGRESSES.forEach { p ->
                frame.at(p)
                translateY(frame.css(frame.find(".sidebar"), "transform")) shouldBe
                    (NAV_SLIDE_PX * reveal(MorphElement.NavigationBar, p.toFloat()) plusOrMinus PX_TOLERANCE)
            }
        }

        test("the transport's side buttons grow from 85 %") {
            val frame = mountFixture()
            PROGRESSES.forEach { p ->
                frame.at(p)
                scaleX(frame.css(frame.find(".np-prev"), "transform")) shouldBe
                    (sidesScaleAt(p) plusOrMinus TOLERANCE)
            }
        }

        test("the scrim darkens Home to 32 % black, linear in progress") {
            val frame = mountFixture()
            val layer = frame.find(".np-layer")
            PROGRESSES.forEach { p ->
                frame.at(p)
                val scrim = frame.window.getComputedStyle(layer, "::before").getPropertyValue("opacity").toDouble()
                scrim shouldBe (SCRIM * p.coerceIn(0.0, 1.0) plusOrMinus TOLERANCE)
            }
        }

        test("at rest (p = 1) every element is exactly where the page puts it") {
            val frame = mountFixture()
            frame.at(1.0)
            listOf(".np-morph", ".np-morph .page-h", ".np-pills", ".np-prev").forEach { selector ->
                frame.opacity(selector) shouldBe (1.0 plusOrMinus TOLERANCE)
            }
            translateY(frame.css(frame.find(".np-morph"), "transform")) shouldBe (0.0 plusOrMinus PX_TOLERANCE)
        }

        test("progress moves only transform and opacity: no layout property is written in terms of --mp-p") {
            layoutRulesOnProgress().shouldBeEmpty()
        }
    })

private fun sidesScaleAt(p: Double): Double = sidesScale(p.toFloat()).toDouble()

/** The y of a computed `matrix(a, b, c, d, x, y)`, or 0 for `none`. */
private fun translateY(transform: String): Double = matrixValues(transform)?.get(MATRIX_Y) ?: 0.0

/** The x scale of a computed `matrix(a, b, c, d, x, y)`, or 1 for `none`. */
private fun scaleX(transform: String): Double = matrixValues(transform)?.get(0) ?: 1.0

private fun matrixValues(transform: String): List<Double>? =
    if (transform == "none") null else transform.substringAfter("(").substringBefore(")").split(",").map { it.trim().toDouble() }

/**
 * Every declaration in the loaded sheets that mentions `--mp-p` (directly, or through the clamped `--mp-q` and the
 * per-element `--t` it feeds) on anything other than transform, transform-origin or opacity, as `selector { property }`.
 */
private fun layoutRulesOnProgress(): List<String> {
    val found = mutableListOf<String>()

    fun collect(rules: dynamic) {
        val length = rules.length as? Int ?: return
        for (i in 0 until length) {
            val rule: dynamic = rules.item(i)
            val selector = rule.selectorText as? String
            if (selector != null) {
                val style = rule.style
                val count = style.length as Int
                for (j in 0 until count) {
                    val property = style.item(j) as String
                    val value = style.getPropertyValue(property) as String
                    val onProgress = PROGRESS_NAMES.any { value.contains(it) }
                    if (onProgress && property !in ALLOWED_ON_PROGRESS && !property.startsWith("--")) {
                        found += "$selector { $property }"
                    }
                }
            }
            val nested = rule.cssRules
            if (nested != null) collect(nested)
        }
    }

    val sheets = document.styleSheets
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        collect(runCatching { sheet.cssRules }.getOrNull()?.asDynamic() ?: continue)
    }
    return found
}

private val PROGRESS_NAMES = listOf("--mp-p", "--mp-q", "var(--t)")

private val ALLOWED_ON_PROGRESS = setOf("transform", "opacity")

private val PROGRESSES = listOf(0.0, 0.3, 0.6, 0.825, 1.0)

private const val PHONE_WIDTH = 390

private const val PHONE_HEIGHT = 844

private const val NAV_SLIDE_PX = 84.0

private const val SCRIM = 0.32

private const val TOLERANCE = 0.01

private const val PX_TOLERANCE = 0.5

private const val MATRIX_Y = 5
