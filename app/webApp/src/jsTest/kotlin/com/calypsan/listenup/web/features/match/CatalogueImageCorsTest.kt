package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mountImg(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

/** The page is cross-origin isolated, so third-party catalogue art must be requested with CORS. */
class CatalogueImageCorsTest :
    FunSpec({
        test("catalogue cover art is requested with CORS") {
            val host = mountImg { Art(url = "https://img.test/a.jpg") }
            host.querySelector("img.bmx-art")!!.getAttribute("crossorigin") shouldBe "anonymous"
            host.remove()
        }

        test("a contributor portrait is requested with CORS and keeps its no-referrer policy") {
            val host = mountImg { Portrait(url = "https://img.test/p.jpg", name = "Ray") }
            val img = host.querySelector("img.pmx-face")!!
            img.getAttribute("crossorigin") shouldBe "anonymous"
            img.getAttribute("referrerpolicy") shouldBe "no-referrer"
            host.remove()
        }
    })
