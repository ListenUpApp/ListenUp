package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.contrast
import com.calypsan.listenup.web.rgb
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * The page's quiet words — the times, the book sentence, "Read by", a chapter's number — sit on the wash, not on paper.
 * The wash is the player's neutral, or a cover's (the shared rule puts it at tone 91 in light and tone 20 in dark), so
 * every tier of ink the page uses has to read at 4.5:1 on all of them, in both themes.
 */
class NowPlayingWashContrastTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun page(
            dark: Boolean,
            wash: String?,
        ): ViewportFrame {
            val frame =
                frames.mount(width = 480, height = 300) {
                    Div(attrs = {
                        classes("np-page")
                        if (wash != null) {
                            classes("cover-themed")
                            style {
                                property("--cover-wash-l", wash)
                                property("--cover-wash-d", wash)
                            }
                        }
                    }) {
                        QUIET_INK.forEach { probe -> Span(attrs = { classes(probe) }) { Text("12:04") } }
                    }
                }
            val root = frame.host.ownerDocument!!.documentElement!!
            if (dark) root.setAttribute("data-theme", "dark") else root.removeAttribute("data-theme")
            return frame
        }

        listOf(false to LIGHT_COVER_WASHES, true to DARK_COVER_WASHES).forEach { (dark, covers) ->
            val theme = if (dark) "dark" else "light"
            test("$theme: every quiet ink on the page reads at 4.5:1 on the neutral and on every cover wash") {
                (listOf<String?>(null) + covers).forEach { wash ->
                    val frame = page(dark, wash)
                    val ground = rgb(frame.css(frame.find(".np-page"), "background-color"))
                    QUIET_INK.forEach { probe ->
                        val ink = rgb(frame.css(frame.find(".np-page .$probe"), "color"))
                        val ratio = contrast(ink, ground)
                        withClue("$probe on ${wash ?: "the neutral"} ($theme): $ratio") {
                            ratio shouldBeGreaterThanOrEqual 4.5
                        }
                    }
                    frames.disposeAll()
                }
            }
        }
    })

/** The page's quieter text, ink-2 and ink-3 alike. */
private val QUIET_INK = listOf("np-time", "np-sentence", "np-by-k", "np-row-n")

/** The shared cover rule's washes around the hue circle: tone 91 (light) and tone 20 (dark) fix the luminance. */
internal val LIGHT_COVER_WASHES = listOf("#F1E1E5", "#EAE5DA", "#D9E9E5", "#E0E6F1")
internal val DARK_COVER_WASHES = listOf("#3C2C31", "#353025", "#223430", "#2A313C")
