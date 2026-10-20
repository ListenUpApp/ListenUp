package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.ViewportFrames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement

/** `WebCoverThemeStyle` mirrors the stylesheet's tokens; a token changed in CSS alone fails here (X3). */
class CoverThemeStyleTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        /** [name] as the frame's sheet resolves it in [dark] or light, as `#RRGGBB`. */
        fun token(
            name: String,
            dark: Boolean,
        ): String {
            val frame = frames.mount(width = 400, height = 200) { Div(attrs = { id("probe") }) }
            val root = frame.host.ownerDocument!!.documentElement!!
            if (dark) root.setAttribute("data-theme", "dark") else root.removeAttribute("data-theme")
            val probe = (document.createElement("span") as HTMLElement).also { frame.host.appendChild(it) }
            probe.style.setProperty("color", "var($name)")
            val channels =
                Regex("""rgba?\(([^)]*)\)""")
                    .find(frame.css(probe, "color"))!!
                    .groupValues[1]
                    .split(",")
                    .take(3)
                    .map { it.trim().toDouble().toInt() }
            return "#" + channels.joinToString("") { it.toString(16).padStart(2, '0') }.uppercase()
        }

        listOf(false, true).forEach { dark ->
            val tokens = if (dark) WebCoverThemeStyle.dark else WebCoverThemeStyle.light
            test("${if (dark) "dark" else "light"}: brand, label, ink and paper are the sheet's") {
                token("--coral-fill", dark) shouldBe cssHex(tokens.brandArgb)
                token("--on-coral", dark) shouldBe cssHex(tokens.onBrandArgb)
                token("--ink", dark) shouldBe cssHex(tokens.inkArgb)
                token("--paper", dark) shouldBe cssHex(requireNotNull(tokens.paperArgb))
            }
        }
    })
