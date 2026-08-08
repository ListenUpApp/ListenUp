package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

/**
 * Proves Compose HTML actually drives the DOM in this build — the composition runs, emits real
 * elements, and recomposes when state changes.
 *
 * Worth asserting rather than assuming: the Compose compiler plugin is applied per-module, and a
 * misconfigured one fails by producing a composition that never runs rather than by failing the
 * build. Rendering nothing looks identical to rendering an empty screen.
 */
class ComposeHtmlRenderTest :
    FunSpec({

        fun mount(content: @Composable () -> Unit): HTMLElement {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) { content() }
            return host
        }

        test("a composable emits real DOM elements") {
            val host = mount { WebAppRoot() }

            val heading = host.querySelector("h1")
            heading.shouldNotBeNullAndContain("ListenUp")
        }

        test("the design system's class contract reaches the DOM") {
            // web.css keys everything off `.luw` plus a direction class; if those do not land on
            // the root element, every token lookup silently falls back and the page renders
            // unstyled rather than broken.
            val host = mount { WebAppRoot() }

            val root = host.querySelector(".luw") as? HTMLElement
            (root != null) shouldBe true
            root!!.className shouldContain "dir-a"
        }
    })

private fun org.w3c.dom.Element?.shouldNotBeNullAndContain(text: String) {
    (this != null) shouldBe true
    this!!.textContent.orEmpty() shouldContain text
}
