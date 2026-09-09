package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.BrowserStoreEnvironment
import com.calypsan.listenup.client.diagnostics.checkBrowserStoreEnvironment
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

/**
 * The boot gate's surface: the sentence naming the broken link has to actually reach the page.
 *
 * A diagnostic that is computed and then dropped is the same spinner it was meant to replace, so
 * what this pins is the one thing the operator sees — the reason, rendered.
 */
class StoreUnavailableTest :
    FunSpec({

        test("the unavailable surface prints the reason it was given") {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            val composition = renderComposable(root = host) { WebAppSurface { StoreUnavailable("Test reason.") } }

            try {
                host.textContent.orEmpty() shouldContain "Test reason."
            } finally {
                // An undisposed composition outlives the spec and keeps answering from a detached
                // tree — see WebAppRootFixtures for the failure mode that taught us.
                composition.dispose()
                host.remove()
            }
        }

        test("the lane's own environment is Ready, so the gate never fires here") {
            // Deliberately duplicates BrowserStoreEnvironmentTest: the gate is only correct while
            // the lane's COOP/COEP headers hold, and this is the spec that fails if they regress.
            checkBrowserStoreEnvironment() shouldBe BrowserStoreEnvironment.Ready
        }
    })
