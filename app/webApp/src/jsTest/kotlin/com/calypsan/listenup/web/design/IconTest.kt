package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

/**
 * The namespace assertion is the reason this spec exists.
 *
 * An `<svg>` created in the HTML namespace still appears in the DOM and still reports the right
 * tag name — it simply draws nothing. So "the icon element exists" proves nothing, and a
 * screenshot would show a gap that reads as a CSS bug. Asserting `namespaceURI` is the only check
 * that actually distinguishes a rendered icon from an invisible one.
 */
class IconTest :
    FunSpec({

        test("an icon is created in the SVG namespace, not the HTML one") {
            val host = mount { Icon(WebIcon.Play) }

            val svg = host.querySelector("svg")
            svg!!.namespaceURI shouldBe SVG_NAMESPACE
        }

        test("paths are in the SVG namespace too") {
            val host = mount { Icon(WebIcon.Play) }

            host.querySelector("path")!!.namespaceURI shouldBe SVG_NAMESPACE
        }

        test("a multi-subpath icon emits one path per subpath") {
            // Trash is six subpaths in one string; splitting is where an off-by-one silently
            // drops the lid or the handle.
            val host = mount { Icon(WebIcon.Trash) }

            host.querySelectorAll("path").length shouldBe WebIcon.Trash.subpaths().size
        }

        test("solid icons fill rather than stroke") {
            // Play is a filled triangle. Stroked, it renders as an outline — visibly wrong, but
            // only if someone looks.
            val host = mount { Icon(WebIcon.Play) }

            host.querySelector("path")!!.getAttribute("fill") shouldBe "currentColor"
        }

        test("outline icons stroke rather than fill") {
            val host = mount { Icon(WebIcon.Download) }

            val path = host.querySelector("path")!!
            path.getAttribute("fill") shouldBe "none"
            path.getAttribute("stroke") shouldBe "currentColor"
        }

        test("every auth icon renders at least one path") {
            // The auth screens are the first consumer of these entries. An enum entry with an
            // empty or malformed path compiles and renders an invisible icon — the exact failure
            // the enum exists to prevent — so assert geometry, not just presence.
            val authIcons =
                listOf(
                    WebIcon.Mail,
                    WebIcon.Lock,
                    WebIcon.Eye,
                    WebIcon.EyeOff,
                    WebIcon.LogIn,
                    WebIcon.LogOut,
                    WebIcon.UserPlus,
                    WebIcon.Clock,
                )

            authIcons.forEach { icon ->
                val host = document.createElement("div") as HTMLElement
                document.body!!.appendChild(host)
                renderComposable(root = host) { Icon(icon) }

                withClue(icon.name) {
                    host.querySelectorAll("svg path").length shouldBeGreaterThan 0
                }
            }
        }
    })
