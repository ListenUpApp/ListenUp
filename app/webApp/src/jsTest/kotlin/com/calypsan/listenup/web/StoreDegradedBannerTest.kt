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
 * The degraded-storage banner: the sentence naming the broken link has to actually reach the page.
 *
 * A diagnostic that is computed and then dropped is the same spinner it was meant to replace, so
 * what this pins is the one thing the reader sees — the reason, rendered.
 *
 * ⛔ This used to be `StoreUnavailableTest`, covering a screen that replaced the app. It no longer
 * replaces anything: the app runs on an in-memory database and this says what that costs. The
 * *behaviour* — that the app boots at all on an insecure origin — cannot be tested from this lane,
 * which runs on trustworthy `localhost`; `web/test/insecure-boot.mjs` covers it.
 */
class StoreDegradedBannerTest :
    FunSpec({

        test("the banner prints the reason it was given") {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            var dismissed = 0
            val composition =
                renderComposable(root = host) {
                    WebAppSurface { StoreDegradedBanner("Test reason.") { dismissed++ } }
                }

            try {
                host.textContent.orEmpty() shouldContain "Test reason."
                // Dismissible, unlike a lapsed session: nothing is broken, and a reader on a server
                // they cannot change should not be told twice.
                (host.querySelector(".lapse-act") as HTMLElement).click()
                dismissed shouldBe 1
            } finally {
                // An undisposed composition outlives the spec and keeps answering from a detached
                // tree — see WebAppRootFixtures for the failure mode that taught us.
                composition.dispose()
                host.remove()
            }
        }

        test("the lane's own environment is Ready, so the banner never fires here") {
            // Deliberately duplicates BrowserStoreEnvironmentTest: the gate is only correct while
            // the lane's COOP/COEP headers hold, and this is the spec that fails if they regress.
            checkBrowserStoreEnvironment() shouldBe BrowserStoreEnvironment.Ready
        }
    })
