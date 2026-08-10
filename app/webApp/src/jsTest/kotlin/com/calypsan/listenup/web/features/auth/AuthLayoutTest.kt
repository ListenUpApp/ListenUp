package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

class AuthLayoutTest :
    FunSpec({

        test("the title, subtitle and content all render in the form column") {
            val host =
                mount {
                    AuthLayout(title = "Sign in", subtitle = "Pick up where you left off.") {
                        Text("FORM")
                    }
                }

            (host.querySelector(".auth-t") as HTMLElement).textContent.orEmpty() shouldContain "Sign in"
            (host.querySelector(".auth-st") as HTMLElement).textContent.orEmpty() shouldContain "Pick up where"
            (host.querySelector(".auth-col") as HTMLElement).textContent.orEmpty() shouldContain "FORM"
        }

        test("the subtitle is optional") {
            val host = mount { AuthLayout(title = "Sign in") { Text("FORM") } }

            host.querySelectorAll(".auth-st").length shouldBe 0
        }

        test("the brand panel is always in the DOM so one media query can hide it") {
            // Same mechanism as the shell's rail: render everything and let CSS decide. A
            // Kotlin-side width check would need a resize listener and would disagree with the
            // sheet at the boundary.
            val host = mount { AuthLayout(title = "Sign in") { Text("FORM") } }

            host.querySelectorAll(".auth-brand").length shouldBe 1
        }

        test("a badge renders only when asked for") {
            val withBadge = mount { AuthLayout(title = "Setup", badge = "Server administrator") { Text("F") } }
            val without = mount { AuthLayout(title = "Sign in") { Text("F") } }

            (withBadge.querySelector(".badge") as HTMLElement).textContent.orEmpty() shouldContain "administrator"
            without.querySelectorAll(".badge").length shouldBe 0
        }
    })
