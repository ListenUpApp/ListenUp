package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

class AccountMenuTest :
    FunSpec({

        test("the menu is closed until asked for") {
            val host = mount { AccountMenu(onSignOut = {}) }

            host.querySelectorAll(".menu").length shouldBe 0
        }

        test("opening reveals a sign-out item") {
            val host = mount { AccountMenu(onSignOut = {}) }

            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".menu") as HTMLElement).textContent.orEmpty() shouldContain "Sign out"
        }

        test("signing out reports it once and closes the menu") {
            var signOuts = 0
            val host = mount { AccountMenu(onSignOut = { signOuts++ }) }
            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".menu-i") as HTMLElement).click()

            // The callback fired synchronously; the menu vanishing is a recomposition.
            signOuts shouldBe 1
            awaitFrame()
            host.querySelectorAll(".menu").length shouldBe 0
        }
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
